package com.semavoice.core.models

import com.semavoice.core.common.Language
import com.semavoice.core.common.SemaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional remote catalog (`catalog.json`) hosted next to model files, e.g. the Amharic
 * fine-tunes produced by tools/convert_whisper_to_ggml.py. Format:
 *
 * [{"id": "whisper-small-amharic-q5_1", "file": "ggml-small-amharic-q5_1.bin", "sizeBytes": 1,
 *   "sha256": "...", "sourceRepo": "...", "license": "Apache-2.0", "base": "small",
 *   "languages": ["am","en"], "displayName": "...", "description": "...", "requiredRamMb": 700,
 *   "minTier": "MID", "url": "https://.../ggml-small-amharic-q5_1.bin"}]
 *
 * Only speech (whisper-ggml) entries are accepted from remote catalogs; translation models are
 * pinned in code. Entries without a 64-hex SHA-256 are ignored. The fetched file is cached on
 * disk so the catalog keeps working offline.
 */
class RemoteCatalog(private val cacheFile: File, private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()) {
    @Serializable
    data class Entry(
        val id: String,
        val file: String = "",
        val sizeBytes: Long = 0,
        val sha256: String = "",
        val url: String? = null,
        val sourceRepo: String = "",
        val license: String = "",
        val base: String = "",
        val languages: List<String> = listOf("am", "en"),
        val displayName: String? = null,
        val description: String? = null,
        val requiredRamMb: Int? = null,
        val minTier: String? = null,
        val version: String = "remote",
        val experimental: Boolean = true,
        /** "whisper-ggml" (default, single [file]) or "onnx-ctc" (multi-file, see [files]). */
        val format: String = "whisper-ggml",
        val files: List<FileEntry> = emptyList(),
        val qualityRank: Int = 0,
        val measuredWer: Double? = null,
    )

    @Serializable
    data class FileEntry(val file: String, val sizeBytes: Long, val sha256: String, val url: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    fun loadCached(baseUrl: String): List<ModelInfo> =
        if (cacheFile.exists()) runCatching { parse(cacheFile.readText(), baseUrl) }.getOrDefault(emptyList()) else emptyList()

    suspend fun fetch(catalogUrl: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(catalogUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val baseUrl = catalogUrl.substringBeforeLast('/')
                val parsed = parse(body, baseUrl)
                if (parsed.isNotEmpty()) { cacheFile.parentFile?.mkdirs(); cacheFile.writeText(body) }
                parsed
            }
        } catch (e: Exception) {
            SemaLog.w("RemoteCatalog", "fetch failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    fun parse(text: String, baseUrl: String): List<ModelInfo> {
        val entries: List<Entry> = runCatching { json.decodeFromString<List<Entry>>(text) }.getOrDefault(emptyList())
        val hex = Regex("[0-9a-fA-F]{64}")
        return entries.filter { e ->
            if (e.files.isNotEmpty()) e.files.all { it.sha256.matches(hex) && it.sizeBytes > 0 }
            else e.sha256.matches(hex) && e.sizeBytes > 0
        }.map { e ->
            val langs = e.languages.map(Language::fromCode).filter { it != Language.AUTO }.ifEmpty { listOf(Language.AMHARIC, Language.ENGLISH) }
            val sizeMb = e.sizeBytes / (1024 * 1024)
            val base = e.base.removePrefix("whisper-")
            val ram = e.requiredRamMb ?: when (base) { "tiny" -> 180; "base" -> 300; "small" -> 700; "medium" -> 1600; else -> (sizeMb * 2 + 150).toInt() }
            val tier = e.minTier?.let { t -> PerformanceTier.entries.firstOrNull { it.name == t } }
                ?: when { ram <= 350 -> PerformanceTier.LOW; ram <= 900 -> PerformanceTier.MID; else -> PerformanceTier.HIGH }
            ModelInfo(
                id = e.id, kind = ModelKind.SPEECH,
                displayName = e.displayName ?: "Whisper ${base.ifBlank { "?" }} Amharic fine-tune",
                description = e.description ?: "Fine-tuned Whisper converted to ggml (${e.sourceRepo}). Required for Amharic dictation.",
                version = e.version,
                files = if (e.files.isNotEmpty()) e.files.map { f -> ModelFile(f.file, f.url ?: "$baseUrl/${f.file}", f.sizeBytes, f.sha256.lowercase()) }
                    else listOf(ModelFile(e.file, e.url ?: "$baseUrl/${e.file}", e.sizeBytes, e.sha256.lowercase())),
                languages = langs, requiredRamMb = ram, minTier = tier,
                license = e.license.ifBlank { "see source repository" },
                attribution = "Fine-tuned Whisper from ${e.sourceRepo}; base model OpenAI Whisper (MIT)",
                format = e.format, recommended = Language.AMHARIC in langs && (e.format == "onnx-ctc" || e.id.endsWith("q5_1")),
                experimental = e.experimental, qualityRank = e.qualityRank, measuredWer = e.measuredWer,
            )
        }
    }
}
