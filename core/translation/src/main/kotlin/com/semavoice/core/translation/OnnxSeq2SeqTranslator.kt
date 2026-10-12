package com.semavoice.core.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.semavoice.core.common.AppError
import com.semavoice.core.common.DispatcherProvider
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.SemaLog
import com.semavoice.core.common.TextNormalizer
import com.semavoice.core.models.Direction
import com.semavoice.core.models.ModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime implementation of [TranslationEngine] for Optimum-exported seq2seq models
 * (encoder_model.onnx + decoder_model_merged.onnx). Supports two families:
 *
 *  - M2M100 (SentencePiece BPE, source `__xx__` + text + </s>, decoder starts `</s>` then forced `__yy__`)
 *  - Marian / OPUS-MT (SentencePiece unigram, text + </s>, decoder starts with <pad>; multilingual
 *    targets selected by a `>>xxx<<` prefix token)
 *
 * Decoding is greedy with a KV cache; beam search is deliberately not implemented on mobile.
 */
class OnnxSeq2SeqTranslator(private val dispatchers: DispatcherProvider) : TranslationEngine {
    private enum class Family { M2M100, MARIAN }

    private val _status = MutableStateFlow<TranslationModelStatus>(TranslationModelStatus.NotLoaded)
    override val status: StateFlow<TranslationModelStatus> = _status.asStateFlow()

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var family = Family.M2M100
    private var sourceTok: SentencePieceTokenizer? = null
    private var targetTok: SentencePieceTokenizer? = null
    private var vocab: HashMap<String, Int> = HashMap()
    private var invVocab: Array<String?> = emptyArray()
    private var eosId = 2
    private var padId = 1
    private var unkId = 3
    private var decoderStartId = 2
    private var numLayers = 12
    private var numHeads = 16
    private var headDim = 64
    private var directions: List<Direction> = emptyList()
    private var modelId = ""
    @Volatile private var cancelled = false

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun initialize(modelId: String, modelDir: File): Outcome<Unit> = withContext(dispatchers.inference) {
        if (this@OnnxSeq2SeqTranslator.modelId == modelId && encoder != null) return@withContext Outcome.Success(Unit)
        releaseInternal()
        _status.value = TranslationModelStatus.Loading(modelId)
        try {
            val info = ModelCatalog.byId(modelId)
            directions = info?.directions ?: emptyList()
            val config = json.parseToJsonElement(File(modelDir, "config.json").readText()).let { it as JsonObject }
            val arch = config["architectures"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content ?: ""
            family = if (arch.contains("Marian")) Family.MARIAN else Family.M2M100
            eosId = config["eos_token_id"]?.jsonPrimitive?.int ?: if (family == Family.MARIAN) 0 else 2
            padId = config["pad_token_id"]?.jsonPrimitive?.int ?: 1
            decoderStartId = config["decoder_start_token_id"]?.jsonPrimitive?.int ?: eosId
            numLayers = config["decoder_layers"]?.jsonPrimitive?.int ?: 12
            numHeads = config["decoder_attention_heads"]?.jsonPrimitive?.int ?: 16
            headDim = (config["d_model"]?.jsonPrimitive?.int ?: 1024) / numHeads

            val vocabJson = json.parseToJsonElement(File(modelDir, "vocab.json").readText()) as JsonObject
            vocab = HashMap(vocabJson.size * 2)
            var maxId = 0
            for ((k, v) in vocabJson) { val id = v.jsonPrimitive.int; vocab[k] = id; if (id > maxId) maxId = id }
            invVocab = arrayOfNulls(maxId + 1)
            for ((k, v) in vocab) invVocab[v] = k
            unkId = vocab["<unk>"] ?: 3

            when (family) {
                Family.M2M100 -> {
                    val spm = SentencePieceTokenizer(SentencePieceModel.load(File(modelDir, "sentencepiece.bpe.model")))
                    sourceTok = spm; targetTok = spm
                }
                Family.MARIAN -> {
                    sourceTok = SentencePieceTokenizer(SentencePieceModel.load(File(modelDir, "source.spm")))
                    targetTok = SentencePieceTokenizer(SentencePieceModel.load(File(modelDir, "target.spm")))
                }
            }

            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            encoder = env.createSession(File(modelDir, "encoder_model_quantized.onnx").absolutePath, opts)
            decoder = env.createSession(File(modelDir, "decoder_model_merged_quantized.onnx").absolutePath, opts)
            this@OnnxSeq2SeqTranslator.modelId = modelId
            _status.value = TranslationModelStatus.Ready(modelId)
            SemaLog.i(TAG, "loaded $modelId ($family, layers=$numLayers heads=$numHeads dim=$headDim)")
            Outcome.Success(Unit)
        } catch (t: Throwable) {
            SemaLog.e(TAG, "load failed", t)
            releaseInternal()
            _status.value = TranslationModelStatus.Error(modelId, t.message ?: "load failed")
            Outcome.Failure(AppError.ModelLoadFailed(modelId, t))
        }
    }

    override fun supports(from: Language, to: Language): Boolean =
        encoder != null && directions.any { it.from == from && it.to == to }

    override suspend fun translate(text: String, from: Language, to: Language): Outcome<TranslationResult> {
        val clean = TextNormalizer.normalize(text)
        if (clean.isEmpty()) return Outcome.Success(TranslationResult(text, "", from, to, 0, modelId))
        if (!supports(from, to)) return Outcome.Failure(AppError.TranslationUnavailable(from, to))
        return withContext(dispatchers.inference) {
            val t0 = System.currentTimeMillis()
            cancelled = false
            try {
                // Translate sentence by sentence: better quality and bounded decoder length.
                val sentences = splitSentences(clean)
                val out = StringBuilder()
                for (s in sentences) {
                    if (cancelled) return@withContext Outcome.Failure(AppError.Cancelled())
                    val translated = translateSentence(s, from, to)
                    if (out.isNotEmpty()) out.append(' ')
                    out.append(translated)
                }
                val result = TextNormalizer.localizePunctuation(TextNormalizer.normalize(out.toString()))
                Outcome.Success(TranslationResult(text, result, from, to, System.currentTimeMillis() - t0, modelId))
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                SemaLog.e(TAG, "translate failed", t)
                Outcome.Failure(AppError.InferenceFailed(t))
            }
        }
    }

    private fun splitSentences(text: String): List<String> {
        val parts = text.split(Regex("(?<=[.!?።፧])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(text) else parts
    }

    private fun m2mLangToken(l: Language): Int? = when (l) {
        Language.AMHARIC -> 128005; Language.ARABIC -> 128006; Language.ENGLISH -> 128022; Language.SOMALI -> 128082
        else -> null
    }

    private fun marianTargetToken(l: Language): String? = when (l) {
        Language.AMHARIC -> ">>amh<<"; Language.TIGRINYA -> ">>tir<<"; Language.SOMALI -> ">>som<<"
        Language.ARABIC -> ">>ara<<"; Language.ENGLISH -> ">>eng<<"; Language.AFAAN_OROMOO -> ">>orm<<"
        else -> null
    }

    private fun translateSentence(sentence: String, from: Language, to: Language): String {
        val enc = encoder ?: error("encoder not loaded")
        val dec = decoder ?: error("decoder not loaded")
        val srcTok = sourceTok ?: error("tokenizer not loaded")

        // ---- source ids ------------------------------------------------------------------
        val pieces = srcTok.encodeToPieces(sentence)
        val src = ArrayList<Long>(pieces.size + 2)
        var forcedFirst: Int? = null
        when (family) {
            Family.M2M100 -> {
                src.add((m2mLangToken(from) ?: error("no lang token")).toLong())
                pieces.forEach { src.add((vocab[it] ?: unkId).toLong()) }
                src.add(eosId.toLong())
                forcedFirst = m2mLangToken(to)
            }
            Family.MARIAN -> {
                // Multilingual-target models need the ">>xxx<<" prefix; to-English models ignore it.
                marianTargetToken(to)?.let { tok -> vocab[tok]?.let { src.add(it.toLong()) } }
                pieces.forEach { src.add((vocab[it] ?: unkId).toLong()) }
                src.add(eosId.toLong())
            }
        }
        val srcLen = src.size
        val maxNew = (srcLen * 2 + 10).coerceAtMost(256)

        // ---- encoder -----------------------------------------------------------------------
        val inputIds = longTensor(src.toLongArray(), longArrayOf(1, srcLen.toLong()))
        val attention = longTensor(LongArray(srcLen) { 1L }, longArrayOf(1, srcLen.toLong()))
        val hidden: OnnxTensor
        inputIds.use {
            val encOut = enc.run(mapOf("input_ids" to inputIds, "attention_mask" to attention))
            val h = encOut[0] as OnnxTensor
            // Copy encoder output into our own tensor so we can close the Result.
            val fb = h.floatBuffer
            val copy = FloatBuffer.allocate(fb.remaining()).put(fb).also { it.flip() }
            hidden = OnnxTensor.createTensor(env, copy, h.info.shape)
            encOut.close()
        }

        // ---- decoder loop with KV cache ---------------------------------------------------
        val decInputNames = dec.inputNames
        val hasCacheBranch = "use_cache_branch" in decInputNames
        val generated = ArrayList<Int>()
        val firstInput: LongArray = if (forcedFirst != null) longArrayOf(decoderStartId.toLong(), forcedFirst.toLong()) else longArrayOf(decoderStartId.toLong())
        if (forcedFirst != null) generated.add(forcedFirst)

        var past: MutableMap<String, OnnxTensor> = HashMap()
        // Empty past tensors for the first (non-cache) step.
        for (l in 0 until numLayers) for (kind in listOf("decoder", "encoder")) for (kv in listOf("key", "value")) {
            val name = "past_key_values.$l.$kind.$kv"
            if (name in decInputNames) past[name] = emptyFloatTensor(longArrayOf(1, numHeads.toLong(), 0, headDim.toLong()))
        }
        var stepInput = firstInput
        var useCache = false
        val recent = ArrayList<Int>()
        try {
            for (step in 0 until maxNew) {
                if (cancelled) break
                val inputs = HashMap<String, OnnxTensor>(past)
                val ids = longTensor(stepInput, longArrayOf(1, stepInput.size.toLong()))
                inputs["input_ids"] = ids
                inputs["encoder_attention_mask"] = attention
                if ("encoder_hidden_states" in decInputNames) inputs["encoder_hidden_states"] = hidden
                val cacheFlag = if (hasCacheBranch) OnnxTensor.createTensor(env, booleanArrayOf(useCache)).also { inputs["use_cache_branch"] = it } else null
                val result = dec.run(inputs)
                ids.close(); cacheFlag?.close()

                val logits = result["logits"].get() as OnnxTensor
                val shape = logits.info.shape // [1, seq, vocab]
                val vocabSize = shape[2].toInt()
                val lastOffset = ((shape[1] - 1) * vocabSize).toInt()
                val fb = logits.floatBuffer
                val next = argmax(fb, lastOffset, vocabSize, generated)

                // Rotate presents into pasts.
                val newPast = HashMap<String, OnnxTensor>()
                for (name in past.keys) {
                    val presentName = name.replace("past_key_values", "present")
                    val present = result[presentName].orElse(null) as? OnnxTensor
                    val src2 = present ?: past.getValue(name)
                    val pfb = src2.floatBuffer
                    val copy = FloatBuffer.allocate(pfb.remaining()).put(pfb).also { it.flip() }
                    newPast[name] = OnnxTensor.createTensor(env, copy, src2.info.shape)
                }
                result.close()
                past.values.forEach { it.close() }
                past = newPast

                if (next == eosId) break
                generated.add(next)
                recent.add(next)
                if (looksDegenerate(generated)) break
                stepInput = longArrayOf(next.toLong())
                useCache = true
            }
        } finally {
            past.values.forEach { it.close() }
            hidden.close(); attention.close()
        }

        // ---- detokenize ------------------------------------------------------------------
        val tgtTok = targetTok ?: error("tokenizer not loaded")
        val outPieces = generated.mapNotNull { id ->
            if (id == eosId || id == padId || id == unkId) null
            else if (family == Family.M2M100 && (id < 4 || id >= 128004)) null
            else invVocab.getOrNull(id)
        }.filter { !(family == Family.MARIAN && it.startsWith(">>") && it.endsWith("<<")) }
        return tgtTok.decodePieces(outPieces)
    }

    /** Greedy pick with pad/special suppression and a no-repeat-4-gram guard. */
    private fun argmax(fb: FloatBuffer, offset: Int, vocabSize: Int, generated: List<Int>): Int {
        val banned = HashSet<Int>()
        banned.add(padId)
        if (family == Family.M2M100) { banned.add(0); for (i in 128004 until vocabSize) banned.add(i) }
        if (generated.size >= 3) {
            val n = generated.size
            val ctx = listOf(generated[n - 3], generated[n - 2], generated[n - 1])
            for (i in 0..n - 4) {
                if (generated[i] == ctx[0] && generated[i + 1] == ctx[1] && generated[i + 2] == ctx[2]) banned.add(generated[i + 3])
            }
        }
        var best = -1
        var bestV = Float.NEGATIVE_INFINITY
        for (i in 0 until vocabSize) {
            val v = fb.get(offset + i)
            if (v > bestV && i !in banned) { bestV = v; best = i }
        }
        return if (best < 0) eosId else best
    }

    private fun looksDegenerate(gen: List<Int>): Boolean {
        if (gen.size < 24) return false
        val tail = gen.takeLast(12)
        return tail.toSet().size <= 2
    }

    private fun longTensor(values: LongArray, shape: LongArray): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(values.size * 8).order(ByteOrder.nativeOrder())
        val lb: LongBuffer = bb.asLongBuffer()
        lb.put(values); lb.flip()
        return OnnxTensor.createTensor(env, lb, shape)
    }

    private fun emptyFloatTensor(shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.allocate(0), shape)

    override fun cancel() { cancelled = true }

    override suspend fun release() = withContext(dispatchers.inference) { releaseInternal() }

    private fun releaseInternal() {
        runCatching { encoder?.close() }; runCatching { decoder?.close() }
        encoder = null; decoder = null
        sourceTok = null; targetTok = null
        vocab = HashMap(); invVocab = emptyArray()
        modelId = ""
        _status.value = TranslationModelStatus.NotLoaded
    }

    private companion object { const val TAG = "OnnxTranslator" }
}
