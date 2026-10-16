package com.semavoice.core.models

import com.semavoice.core.common.Language
import kotlinx.serialization.Serializable

@Serializable
enum class ModelKind { SPEECH, TRANSLATION }

@Serializable
enum class PerformanceTier { LOW, MID, HIGH }

/** One downloadable file of a model. Checksums are SHA-256 and must be verified before use. */
@Serializable
data class ModelFile(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class ModelInfo(
    val id: String,
    val kind: ModelKind,
    val displayName: String,
    val description: String,
    val version: String,
    val files: List<ModelFile>,
    /** Languages this model is tested and enabled for. Never list untested languages. */
    val languages: List<Language>,
    /** For translation models: directions that are enabled. */
    val directions: List<Direction> = emptyList(),
    /** Rough RAM needed while loaded (file size + working memory). */
    val requiredRamMb: Int,
    /** Minimum device tier on which inference is usable (RTF < ~1.5 on short clips). */
    val minTier: PerformanceTier,
    val license: String,
    val attribution: String,
    /** Family-specific hints; e.g. "whisper-ggml", "onnx-seq2seq-m2m100", "onnx-seq2seq-marian". */
    val format: String,
    /** Recommended default for its kind. */
    val recommended: Boolean = false,
    /** Set when the model is a fine-tune that still needs human quality verification. */
    val experimental: Boolean = false,
    /** Higher = better measured accuracy for its languages (docs/ASR_MODEL_COMPARISON.md). 0 = unranked. */
    val qualityRank: Int = 0,
    /** Measured corpus WER on the 30-clip FLEURS am_et benchmark, null when not measured. */
    val measuredWer: Double? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

@Serializable
data class Direction(val from: Language, val to: Language)

/**
 * Built-in catalog. Every entry here has been license-checked (see docs/LICENSES.md) and
 * its checksums were taken from the hosting repository's LFS metadata on 2026-10-12.
 *
 * Amharic speech: stock Whisper is unusable for Amharic (WER > 100% in the Whisper paper),
 * so the Amharic entries point at fine-tuned checkpoints converted to ggml. Those converted
 * files are produced by tools/convert_whisper_to_ggml.py and hosted at [AMHARIC_MODEL_BASE_URL];
 * their checksums are filled in by the conversion script (see docs/MODEL_COMPATIBILITY.md).
 */
object ModelCatalog {
    private const val HF_WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    private const val HF_M2M = "https://huggingface.co/Xenova/m2m100_418M/resolve/main"
    private const val HF_MUL_EN = "https://huggingface.co/Xenova/opus-mt-mul-en/resolve/main"
    private const val HF_EN_MUL = "https://huggingface.co/Xenova/opus-mt-en-mul/resolve/main"

    const val WHISPER_LICENSE = "MIT (whisper.cpp ggml conversion) / MIT (OpenAI Whisper weights)"

    private fun whisper(
        id: String, name: String, desc: String, file: String, size: Long, sha: String,
        ram: Int, tier: PerformanceTier, recommended: Boolean = false,
    ) = ModelInfo(
        id = id, kind = ModelKind.SPEECH, displayName = name, description = desc, version = "ggml-2024",
        files = listOf(ModelFile(file, "$HF_WHISPER/$file", size, sha)),
        // Somali is NOT listed: stock Whisper exceeds 100% WER on it (Whisper paper, Table 13).
        languages = listOf(Language.ENGLISH, Language.ARABIC),
        requiredRamMb = ram, minTier = tier, license = WHISPER_LICENSE,
        attribution = "OpenAI Whisper, converted by ggml.ai (ggerganov/whisper.cpp)",
        format = "whisper-ggml", recommended = recommended,
    )

    val whisperTinyQ5 = whisper(
        "whisper-tiny-q5_1", "Whisper Tiny (quantized)",
        "Fastest multilingual model. Good for short English phrases on low-end phones.",
        "ggml-tiny-q5_1.bin", 32_152_673, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        ram = 180, tier = PerformanceTier.LOW,
    )
    val whisperBaseQ5 = whisper(
        "whisper-base-q5_1", "Whisper Base (quantized)",
        "Balanced speed and accuracy for English, Arabic and Somali.",
        "ggml-base-q5_1.bin", 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        ram = 300, tier = PerformanceTier.LOW, recommended = true,
    )
    val whisperSmallQ5 = whisper(
        "whisper-small-q5_1", "Whisper Small (quantized)",
        "Noticeably more accurate; needs a mid-range phone.",
        "ggml-small-q5_1.bin", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        ram = 700, tier = PerformanceTier.MID,
    )
    val whisperMediumQ5 = whisper(
        "whisper-medium-q5_0", "Whisper Medium (quantized)",
        "High accuracy; flagship phones only. Slow on anything else.",
        "ggml-medium-q5_0.bin", 539_212_467, "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f",
        ram = 1600, tier = PerformanceTier.HIGH,
    )
    val whisperLargeTurboQ5 = whisper(
        "whisper-large-v3-turbo-q5_0", "Whisper Large v3 Turbo (quantized)",
        "Best stock accuracy for English and Arabic; 3 GB RAM needed.",
        "ggml-large-v3-turbo-q5_0.bin", 574_041_195, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        ram = 2200, tier = PerformanceTier.HIGH,
    )

    /**
     * Amharic fine-tunes. Files are produced by tools/convert_whisper_to_ggml.py from
     * Apache-2.0 / MIT checkpoints and must be hosted by the app publisher. URL and checksum
     * are read from the remote catalog (see [RemoteCatalog]); entries stay hidden until
     * a verified checksum is present.
     */
    const val AMHARIC_MODEL_BASE_URL = "https://github.com/ethiokorma/sema-voice/releases/download/models-2026.10"

    /**
     * Measured 2026-10-12 on 30 FLEURS am_et clips (docs/TEST_RESULTS.md): the Whisper Tiny
     * fine-tune b1n1yam/shook-tiny-amharic-600hr (Apache-2.0) produces real Amharic (WER 0.81
     * raw, 12/30 clips under 0.5, hurt by decoder repetition loops). The seyyaw "small" fine-tune
     * emitted Hebrew/English garbage and is rejected. The SHA-256 below is the converted q5_1
     * file in dist/models/catalog.json; the URL becomes live once the publisher uploads it (the
     * entry stays hidden until then because sizeBytes/sha256 are also served by catalog.json).
     */
    val whisperAmharicTinyQ5 = ModelInfo(
        id = "tiny-amharic-q5_1", kind = ModelKind.SPEECH,
        displayName = "Whisper Tiny, Amharic fine-tune (quantized)",
        description = "Fine-tuned Whisper Tiny for Amharic (31 MB). Measured WER 0.81 on FLEURS; experimental but the only working Amharic option today.",
        version = "2026.10",
        files = listOf(ModelFile("ggml-tiny-amharic-q5_1.bin", "$AMHARIC_MODEL_BASE_URL/ggml-tiny-amharic-q5_1.bin", 32_152_690, "752a5116ae76f929cfc832c0ee9663841ec78bc0e58f28211d26f7a86d39c704")),
        languages = listOf(Language.AMHARIC, Language.ENGLISH),
        requiredRamMb = 180, minTier = PerformanceTier.LOW,
        license = "Apache-2.0 (b1n1yam/shook-tiny-amharic-600hr) / MIT (Whisper base)",
        attribution = "Fine-tuned Whisper Tiny by b1n1yam (shook-tiny-amharic-600hr); base model OpenAI Whisper",
        format = "whisper-ggml", recommended = true, experimental = true,
        qualityRank = 1, measuredWer = 0.812,
    )

    /**
     * badrex/Ethio-ASR-amharic: w2v-BERT 2.0 CTC (600M), CC-BY-4.0, trained on WAXAL Amharic
     * (documented data; Google lists the Amharic WAXAL split as CC-BY-SA-4.0, review before paid
     * release). Measured 2026-10-15 on 30 FLEURS am_et clips, through ONNX Runtime with the int8
     * file the app downloads (v2: pointwise convs rewritten as MatMul, int8 per-channel, 652 MB):
     * WER 0.222 / CER 0.072, 26/30 clips under 0.5, no loops (fp32: 0.219 / 0.072; v1 876 MB: 0.235). RTF 1.25 on a 4-core Xeon E5-1607; phone speed not measured yet.
     * CTC cannot loop. Hosted as GitHub release assets (models-2026.10) of ethiokorma/sema-voice; built with tools/export_ctc_onnx.py. Int8 is MatMul-only (ORT has no ConvInteger kernel). Emits "።" and digits.
     * Alternative with equal accuracy: snapwre/hohe-asr-amharic (0.221 fp32, corpus undisclosed).
     */
    val ethioAsrAmharicCtc = ModelInfo(
        id = "ethio-asr-amharic-ctc-int8", kind = ModelKind.SPEECH,
        displayName = "Amharic high accuracy (w2v-BERT CTC, int8)",
        description = "Best measured Amharic model: 22% word error on FLEURS vs 81% for the tiny Whisper model. 652 MB; needs a phone with 6 GB RAM.",
        version = "2026.10",
        files = listOf(
            ModelFile("model_int8.onnx", "$AMHARIC_MODEL_BASE_URL/ethio-asr-ctc-v2-model_int8.onnx", 652_228_057, "1cfce4733c3a54b434dfb62ed1fbf3da17607d8422c2bbd814d0fedc57cb7644"),
            ModelFile("vocab.json", "$AMHARIC_MODEL_BASE_URL/ethio-asr-ctc-vocab.json", 4_700, "3f4a05c98277736556609871d76c8648b86fe04ac4c51e07f20dfbf7740c3acc"),
            ModelFile("ctc_config.json", "$AMHARIC_MODEL_BASE_URL/ethio-asr-ctc-ctc_config.json", 214, "0f41f52d4d95eab957ed0c7b5c1a22b6b83d8e7d79fa90a4c053090eeb8afa86"),
        ),
        languages = listOf(Language.AMHARIC),
        requiredRamMb = 1400, minTier = PerformanceTier.HIGH,
        license = "CC-BY-4.0 (badrex/Ethio-ASR-amharic) - attribution required; data: WAXAL",
        attribution = "Ethio-ASR (Badr M. Abdullah et al., arXiv:2603.23654), w2v-BERT 2.0 fine-tuned on WAXAL Amharic",
        format = "onnx-ctc", recommended = true, experimental = true,
        qualityRank = 10, measuredWer = 0.222,
    )

    // ---- Translation --------------------------------------------------------------

    val m2m100Int8 = ModelInfo(
        id = "m2m100-418m-int8", kind = ModelKind.TRANSLATION,
        displayName = "M2M100 418M (int8)",
        description = "Meta's many-to-many translator. Amharic <-> English, Somali, Arabic. 630 MB; needs a mid-range phone.",
        version = "xenova-onnx-int8",
        files = listOf(
            ModelFile("encoder_model_quantized.onnx", "$HF_M2M/onnx/encoder_model_quantized.onnx", 287_856_370, "13a94e354a9140764eb81102d77d3ec6952d796e6f113c651eeb3c3443da0386"),
            ModelFile("decoder_model_merged_quantized.onnx", "$HF_M2M/onnx/decoder_model_merged_quantized.onnx", 344_128_178, "007654bcabb6cea6fd3bde34ce933137b431330b3755781145d7b6906270b45a"),
            ModelFile("sentencepiece.bpe.model", "$HF_M2M/sentencepiece.bpe.model", 2_423_393, "d8f7c76ed2a5e0822be39f0a4f95a55eb19c78f4593ce609e2edbc2aea4d380a"),
            ModelFile("vocab.json", "$HF_M2M/vocab.json", 3_708_092, "b6e77e474aeea8f441363aca7614317c06381f3eacfe10fb9856d5081d1074cc"),
            ModelFile("config.json", "$HF_M2M/config.json", 908, "1dbdf77ddc7809acd4c54ccf0eab46f840b40174afb1b6f6de8787244e832938"),
        ),
        languages = listOf(Language.AMHARIC, Language.ENGLISH, Language.SOMALI, Language.ARABIC),
        directions = listOf(
            Direction(Language.AMHARIC, Language.ENGLISH), Direction(Language.ENGLISH, Language.AMHARIC),
            Direction(Language.SOMALI, Language.ENGLISH), Direction(Language.ENGLISH, Language.SOMALI),
            Direction(Language.ARABIC, Language.ENGLISH), Direction(Language.ENGLISH, Language.ARABIC),
            Direction(Language.AMHARIC, Language.ARABIC), Direction(Language.ARABIC, Language.AMHARIC),
        ),
        requiredRamMb = 1100, minTier = PerformanceTier.MID,
        license = "MIT (facebook/m2m100_418M); ONNX export by Xenova",
        attribution = "Fan et al., Beyond English-Centric Multilingual Machine Translation (Meta AI)",
        format = "onnx-seq2seq-m2m100", recommended = true,
    )

    val opusMulEnInt8 = ModelInfo(
        id = "opus-mt-mul-en-int8", kind = ModelKind.TRANSLATION,
        displayName = "OPUS-MT multilingual to English (int8)",
        description = "Small, fast Amharic/Tigrinya/Somali -> English translator (112 MB). Lower quality than M2M100.",
        version = "xenova-onnx-int8",
        files = listOf(
            ModelFile("encoder_model_quantized.onnx", "$HF_MUL_EN/onnx/encoder_model_quantized.onnx", 52_475_294, "5ce609a524375dbdd9c66b62b82b41abc667799022667ce424f20c245bd56925"),
            ModelFile("decoder_model_merged_quantized.onnx", "$HF_MUL_EN/onnx/decoder_model_merged_quantized.onnx", 59_785_040, "6add167a0cd3f78aa298b8f927d2e8645e33cb17df11e92967f0c5b5703c8c4d"),
            ModelFile("source.spm", "$HF_MUL_EN/source.spm", 706_917, "c4a99ea3602b29fbf901ade8b93a45efa3d7c64eab8fc5fa812383efa327a87d"),
            ModelFile("target.spm", "$HF_MUL_EN/target.spm", 791_194, "c6dce5fa58fcd7dde9e81e279b8c075bf42ee558278f73d6fb48e342029d7f19"),
            ModelFile("vocab.json", "$HF_MUL_EN/vocab.json", 1_552_293, "b1a8216e6f605e72eff0ebfbfc4956e3a906f965c18943843650595e7639bafb"),
            ModelFile("config.json", "$HF_MUL_EN/config.json", 1390, "115093532b9893a6e3ec64951db15bafbad200f34929304f9570c3cd7f1dff94"),
        ),
        languages = listOf(Language.AMHARIC, Language.TIGRINYA, Language.SOMALI, Language.ARABIC, Language.ENGLISH),
        directions = listOf(
            Direction(Language.AMHARIC, Language.ENGLISH), Direction(Language.TIGRINYA, Language.ENGLISH),
            Direction(Language.SOMALI, Language.ENGLISH), Direction(Language.ARABIC, Language.ENGLISH),
        ),
        requiredRamMb = 350, minTier = PerformanceTier.LOW,
        license = "Apache-2.0 (Helsinki-NLP/opus-mt-mul-en); ONNX export by Xenova",
        attribution = "Tiedemann & Thottingal, OPUS-MT (Helsinki-NLP)",
        format = "onnx-seq2seq-marian",
    )

    val opusEnMulInt8 = ModelInfo(
        id = "opus-mt-en-mul-int8", kind = ModelKind.TRANSLATION,
        displayName = "OPUS-MT English to multilingual (int8)",
        description = "Small English -> Amharic/Tigrinya/Somali translator (112 MB). Basic quality; M2M100 is better for Amharic.",
        version = "xenova-onnx-int8",
        files = listOf(
            ModelFile("encoder_model_quantized.onnx", "$HF_EN_MUL/onnx/encoder_model_quantized.onnx", 52_443_550, "f10f49c2691043ba7cfd2a85d86f84dddda428d4209cf9b495f6e114b313737a"),
            ModelFile("decoder_model_merged_quantized.onnx", "$HF_EN_MUL/onnx/decoder_model_merged_quantized.onnx", 59_753_048, "35c4c88823c8fbe44aa57bbe8543abc0c0269f321cbf8075965fd697c9569caa"),
            ModelFile("source.spm", "$HF_EN_MUL/source.spm", 790_473, "9129bf81ef9d5fcc1797ea3061e789dc5f9e5602786b0490238d207d4219e0f5"),
            ModelFile("target.spm", "$HF_EN_MUL/target.spm", 706_659, "6a0a52318dac3247455ec889eb3bd8da9bdf234974be8b9b0f99ad5378f474c9"),
            ModelFile("vocab.json", "$HF_EN_MUL/vocab.json", 1_548_192, "6ded021d39a6bd63339a045a0868f69d29aa8f3b6e53f43af2037246a5aeaca0"),
            ModelFile("config.json", "$HF_EN_MUL/config.json", 1390, "fc12f9f431b4fb8dae1f7776c7ad3f9a5cf0fd6321231e11b45e35a2b11730f3"),
        ),
        languages = listOf(Language.ENGLISH, Language.AMHARIC, Language.TIGRINYA, Language.SOMALI, Language.ARABIC),
        directions = listOf(
            Direction(Language.ENGLISH, Language.AMHARIC), Direction(Language.ENGLISH, Language.TIGRINYA),
            Direction(Language.ENGLISH, Language.SOMALI), Direction(Language.ENGLISH, Language.ARABIC),
        ),
        requiredRamMb = 350, minTier = PerformanceTier.LOW,
        license = "Apache-2.0 (Helsinki-NLP/opus-mt-en-mul); ONNX export by Xenova",
        attribution = "Tiedemann & Thottingal, OPUS-MT (Helsinki-NLP)",
        format = "onnx-seq2seq-marian",
    )

    val builtIn: List<ModelInfo> = listOf(
        ethioAsrAmharicCtc, whisperAmharicTinyQ5,
        whisperTinyQ5, whisperBaseQ5, whisperSmallQ5, whisperMediumQ5, whisperLargeTurboQ5,
        m2m100Int8, opusMulEnInt8, opusEnMulInt8,
    )

    fun byId(id: String): ModelInfo? = builtIn.firstOrNull { it.id == id }

    /** A model is downloadable only when every file has a real checksum. */
    fun isPublishable(m: ModelInfo): Boolean = m.files.all { it.sha256.length == 64 && it.sizeBytes > 0 }
}
