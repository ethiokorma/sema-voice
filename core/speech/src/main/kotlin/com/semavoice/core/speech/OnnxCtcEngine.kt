package com.semavoice.core.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.semavoice.core.common.AppError
import com.semavoice.core.common.DispatcherProvider
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.PcmAudio
import com.semavoice.core.common.SemaLog
import com.semavoice.core.common.TextNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Speech engine for CTC acoustic models exported to ONNX (w2v-BERT 2.0, e.g.
 * snapwre/hohe-asr-amharic). CTC emits each output frame independently, so it cannot fall into
 * the autoregressive repetition loops Whisper shows on Amharic.
 *
 * Model directory layout (produced by tools/export_ctc_onnx.py):
 *   model.onnx (or model_int8.onnx), vocab.json, ctc_config.json {"blank_id":408,"word_delimiter":"|","languages":["am"]}
 *
 * Long audio is processed in windows of [windowSeconds] with no overlap at silence-free cuts;
 * w2v-BERT attention cost grows quadratically, so windows bound memory on phones.
 */
class OnnxCtcEngine(
    private val dispatchers: DispatcherProvider,
    private val windowSeconds: Int = 20,
) : SpeechRecognitionEngine {
    private val _status = MutableStateFlow<SpeechModelStatus>(SpeechModelStatus.NotLoaded)
    override val status: StateFlow<SpeechModelStatus> = _status.asStateFlow()
    private val _progress = MutableStateFlow(0)
    override val progress: StateFlow<Int> = _progress.asStateFlow()

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private var decoder: CtcDecoder? = null
    private var languages: List<Language> = listOf(Language.AMHARIC)
    private val frontEnd = W2vBertFeatureExtractor()
    @Volatile private var cancelled = false
    @Volatile var currentModelId: String = ""; private set
    val isLoaded: Boolean get() = session != null

    override suspend fun initialize(modelId: String, modelPath: String): Outcome<Unit> = withContext(dispatchers.inference) {
        if (session != null && currentModelId == modelId) return@withContext Outcome.Success(Unit)
        releaseInternal()
        _status.value = SpeechModelStatus.Loading(modelId)
        try {
            val file = File(modelPath)
            val dir = file.parentFile ?: error("no model dir")
            val vocab = parseFlatJsonInts(File(dir, "vocab.json").readText())
            val cfgText = File(dir, "ctc_config.json").takeIf { it.exists() }?.readText() ?: "{}"
            val blank = Regex("\"blank_id\"\\s*:\\s*(\\d+)").find(cfgText)?.groupValues?.get(1)?.toInt() ?: vocab["[PAD]"] ?: 0
            languages = Regex("\"languages\"\\s*:\\s*\\[([^\\]]*)]").find(cfgText)?.groupValues?.get(1)
                ?.split(',')?.map { Language.fromCode(it.trim().trim('"')) }?.filter { it != Language.AUTO }?.ifEmpty { null }
                ?: listOf(Language.AMHARIC)
            decoder = CtcDecoder(vocab, blank)
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env.createSession(file.absolutePath, opts)
            currentModelId = modelId
            _status.value = SpeechModelStatus.Ready(modelId, multilingual = languages.size > 1)
            SemaLog.i(TAG, "loaded CTC model $modelId, inputs=${session!!.inputNames}")
            Outcome.Success(Unit)
        } catch (t: Throwable) {
            SemaLog.e(TAG, "load failed", t)
            releaseInternal()
            _status.value = SpeechModelStatus.Error(modelId, t.message ?: "load failed")
            Outcome.Failure(if (t is OutOfMemoryError) AppError.OutOfMemoryRisk(modelId, 0, 0) else AppError.ModelLoadFailed(modelId, t))
        }
    }

    override suspend fun transcribe(audio: PcmAudio, options: TranscribeOptions): Outcome<Transcription> {
        if (audio.isEmpty || audio.durationMs < 200) return Outcome.Failure(AppError.EmptyRecording())
        if (options.language != Language.AUTO && options.language !in languages) return Outcome.Failure(AppError.UnsupportedLanguage(options.language))
        return withContext(dispatchers.inference) {
            val s = session ?: return@withContext Outcome.Failure(AppError.ModelNotInstalled(currentModelId.ifEmpty { "speech" }))
            val dec = decoder ?: return@withContext Outcome.Failure(AppError.ModelNotInstalled(currentModelId))
            cancelled = false
            val t0 = System.currentTimeMillis()
            try {
                val win = windowSeconds * PcmAudio.WHISPER_SAMPLE_RATE
                val pieces = ArrayList<String>()
                val segments = ArrayList<TranscriptSegment>()
                var start = 0
                while (start < audio.samples.size) {
                    if (cancelled) return@withContext Outcome.Failure(AppError.Cancelled())
                    val end = minOf(audio.samples.size, start + win)
                    val chunk = audio.samples.copyOfRange(start, end)
                    if (chunk.size >= 8000) {
                        val text = runWindow(s, dec, chunk)
                        if (text.isNotBlank()) {
                            pieces.add(text)
                            segments.add(TranscriptSegment(start * 1000L / 16000, end * 1000L / 16000, text))
                        }
                    }
                    _progress.value = (end * 100L / audio.samples.size).toInt()
                    start = end
                }
                val text = TextNormalizer.normalize(pieces.joinToString(" "))
                Outcome.Success(Transcription(text, segments, if (options.language == Language.AUTO) languages.first() else options.language,
                    System.currentTimeMillis() - t0, audio.durationMs, currentModelId))
            } catch (t: Throwable) {
                SemaLog.e(TAG, "inference failed", t)
                Outcome.Failure(if (t is OutOfMemoryError) AppError.OutOfMemoryRisk(currentModelId, 0, 0) else AppError.InferenceFailed(t))
            }
        }
    }

    private fun runWindow(s: OrtSession, dec: CtcDecoder, samples: FloatArray): String {
        val (feats, frames) = frontEnd.extract(samples)
        if (frames == 0) return ""
        val dim = frontEnd.featureDim
        val inputs = HashMap<String, OnnxTensor>()
        val features = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), longArrayOf(1, frames.toLong(), dim.toLong()))
        inputs[s.inputNames.firstOrNull { it.contains("feature") } ?: s.inputNames.first()] = features
        val maskName = s.inputNames.firstOrNull { it.contains("mask") }
        // The exported Ethio-ASR graph takes int32 attention_mask; other exports may use int64.
        val maskIsInt32 = maskName?.let { (s.inputInfo[it]?.info as? ai.onnxruntime.TensorInfo)?.type == ai.onnxruntime.OnnxJavaType.INT32 } ?: false
        val mask = maskName?.let {
            if (maskIsInt32) OnnxTensor.createTensor(env, java.nio.IntBuffer.wrap(IntArray(frames) { 1 }), longArrayOf(1, frames.toLong()))
            else OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(frames) { 1L }), longArrayOf(1, frames.toLong()))
        }
        if (maskName != null) inputs[maskName] = mask!!
        try {
            s.run(inputs).use { result ->
                val logits = result[0] as OnnxTensor
                val shape = logits.info.shape // [1, T', V]
                val fb = logits.floatBuffer
                val arr = FloatArray(fb.remaining()).also { fb.get(it) }
                return dec.decode(arr, shape[1].toInt(), shape[2].toInt())
            }
        } finally { features.close(); mask?.close() }
    }

    override fun cancel() { cancelled = true }
    override suspend fun release() = withContext(dispatchers.inference) { releaseInternal() }
    private fun releaseInternal() {
        runCatching { session?.close() }
        session = null; decoder = null; currentModelId = ""
        _status.value = SpeechModelStatus.NotLoaded
    }
    override fun getSupportedLanguages(): List<Language> = languages

    companion object {
        private const val TAG = "OnnxCtcEngine"

        /** Minimal parser for {"token": id, ...} (vocab.json); avoids a JSON dependency in this module. */
        fun parseFlatJsonInts(json: String): Map<String, Int> {
            val out = HashMap<String, Int>()
            val re = Regex("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*(-?\\d+)")
            for (m in re.findAll(json)) {
                val key = m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
                    .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }
                out[key] = m.groupValues[2].toInt()
            }
            return out
        }
    }
}
