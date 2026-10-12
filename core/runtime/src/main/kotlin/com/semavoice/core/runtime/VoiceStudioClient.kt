package com.semavoice.core.runtime

import com.semavoice.core.common.AppError
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.SemaLog
import com.semavoice.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional connection to a user-run Voice Studio server (desktop). Two servers are
 * supported through their documented HTTP APIs:
 *
 *  - msrbuilds/voice-studio (MIT):  POST {base}/api/synthesize  -> WAV bytes  (text-to-speech, cloning)
 *  - debpalash/VoiceStudio (AGPL-3.0, used only as an external process over HTTP; no code is linked):
 *        POST {base}/v1/audio/transcriptions (OpenAI-compatible, multipart "file") -> {"text": ...}
 *
 * Every call requires [SemaSettings.voiceStudioConsentGiven]; audio is never sent without it.
 * The engine is always reported as *remote* in the UI.
 */
class VoiceStudioClient(private val settings: SettingsRepository) {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    data class ServerInfo(val baseUrl: String, val kind: Kind)
    enum class Kind { MSR_VOICE_STUDIO, DEBPALASH_VOICESTUDIO, UNKNOWN }

    private suspend fun requireConsent(): Outcome<String> {
        val s = settings.current()
        if (!s.voiceStudioEnabled || !s.voiceStudioConsentGiven) {
            return Outcome.Failure(AppError.Unknown(IllegalStateException("Voice Studio not enabled/consented")))
        }
        val base = s.voiceStudioBaseUrl.trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return Outcome.Failure(AppError.Unknown(IllegalArgumentException("Voice Studio URL must be http(s)")))
        }
        return Outcome.Success(base)
    }

    /** Probes which server is running. */
    suspend fun detect(): Outcome<ServerInfo> = withContext(Dispatchers.IO) {
        val base = when (val c = requireConsent()) { is Outcome.Failure -> return@withContext c; is Outcome.Success -> c.value }
        try {
            client.newCall(Request.Builder().url("$base/.well-known/voicestudio-speech").build()).execute().use {
                if (it.isSuccessful) return@withContext Outcome.Success(ServerInfo(base, Kind.DEBPALASH_VOICESTUDIO))
            }
            client.newCall(Request.Builder().url("$base/api/engines").build()).execute().use {
                if (it.isSuccessful) return@withContext Outcome.Success(ServerInfo(base, Kind.MSR_VOICE_STUDIO))
            }
            Outcome.Success(ServerInfo(base, Kind.UNKNOWN))
        } catch (e: Exception) {
            Outcome.Failure(AppError.NetworkUnavailable())
        }
    }

    /** msrbuilds/voice-studio text-to-speech. Returns the WAV file written to [out]. */
    suspend fun synthesize(text: String, language: Language, voice: String?, out: File): Outcome<File> = withContext(Dispatchers.IO) {
        val base = when (val c = requireConsent()) { is Outcome.Failure -> return@withContext c; is Outcome.Success -> c.value }
        val body = buildJsonObject {
            put("text", text)
            put("language", language.code)
            if (voice != null) put("voice", voice)
            put("format", "wav")
        }.toString().toRequestBody("application/json".toMediaType())
        try {
            client.newCall(Request.Builder().url("$base/api/synthesize").post(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Outcome.Failure(AppError.InferenceFailed(RuntimeException("HTTP ${resp.code}")))
                val ct = resp.header("Content-Type") ?: ""
                val bytes = resp.body?.bytes() ?: return@withContext Outcome.Failure(AppError.InferenceFailed())
                if (ct.contains("json")) {
                    // {"audio": "<base64 wav>"} variant
                    val obj = json.parseToJsonElement(String(bytes)) as JsonObject
                    val b64 = obj["audio"]?.jsonPrimitive?.content ?: return@withContext Outcome.Failure(AppError.InferenceFailed())
                    out.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                } else out.writeBytes(bytes)
                Outcome.Success(out)
            }
        } catch (e: Exception) {
            SemaLog.w(TAG, "synthesize failed", e)
            Outcome.Failure(AppError.NetworkUnavailable())
        }
    }

    /** debpalash/VoiceStudio transcription (OpenAI-compatible). Only called after explicit consent. */
    suspend fun transcribe(wav: File, language: Language): Outcome<String> = withContext(Dispatchers.IO) {
        val base = when (val c = requireConsent()) { is Outcome.Failure -> return@withContext c; is Outcome.Success -> c.value }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .apply { if (language != Language.AUTO) addFormDataPart("language", language.code) }
            .build()
        try {
            client.newCall(Request.Builder().url("$base/v1/audio/transcriptions").post(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Outcome.Failure(AppError.InferenceFailed(RuntimeException("HTTP ${resp.code}")))
                val obj = json.parseToJsonElement(resp.body?.string() ?: "{}") as JsonObject
                Outcome.Success(obj["text"]?.jsonPrimitive?.content ?: "")
            }
        } catch (e: Exception) {
            Outcome.Failure(AppError.NetworkUnavailable())
        }
    }

    private companion object { const val TAG = "VoiceStudio" }
}
