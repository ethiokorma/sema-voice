package com.semavoice.core.translation

import java.io.File
import java.io.InputStream

/**
 * Minimal reader for SentencePiece's `ModelProto` (sentencepiece_model.proto). Only the
 * fields needed for inference are decoded:
 *
 *   ModelProto { repeated SentencePiece pieces = 1; TrainerSpec trainer_spec = 2; NormalizerSpec normalizer_spec = 3; }
 *   SentencePiece { string piece = 1; float score = 2; Type type = 3; }  // NORMAL=1, UNKNOWN=2, CONTROL=3, USER_DEFINED=4, BYTE=6, UNUSED=5
 *   TrainerSpec   { ModelType model_type = 3; int32 unk_id = 40; ... }   // UNIGRAM=1, BPE=2
 *   NormalizerSpec{ string name = 1; bytes precompiled_charsmap = 2; bool add_dummy_prefix = 3;
 *                   bool remove_extra_whitespaces = 4; bool escape_whitespaces = 5; }
 */
class SentencePieceModel private constructor(
    val pieces: List<Piece>,
    val modelType: ModelType,
    val unkId: Int,
    val addDummyPrefix: Boolean,
    val removeExtraWhitespaces: Boolean,
    val escapeWhitespaces: Boolean,
    val normalizerName: String,
) {
    enum class ModelType { UNIGRAM, BPE }
    enum class PieceType { NORMAL, UNKNOWN, CONTROL, USER_DEFINED, UNUSED, BYTE }
    data class Piece(val piece: String, val score: Float, val type: PieceType)

    val pieceToId: Map<String, Int> = HashMap<String, Int>(pieces.size * 2).also { m ->
        pieces.forEachIndexed { i, p -> if (p.type != PieceType.UNUSED) m.putIfAbsent(p.piece, i) }
    }
    val minScore: Float = pieces.filter { it.type == PieceType.NORMAL }.minOfOrNull { it.score } ?: 0f

    fun idOf(piece: String): Int = pieceToId[piece] ?: unkId
    fun pieceOf(id: Int): String = pieces[id].piece

    companion object {
        fun load(file: File): SentencePieceModel = file.inputStream().buffered().use { load(it.readBytes()) }
        fun load(input: InputStream): SentencePieceModel = load(input.readBytes())

        fun load(bytes: ByteArray): SentencePieceModel {
            val pieces = ArrayList<Piece>(32_000)
            var modelType = ModelType.UNIGRAM
            var unkId = 0
            var addDummyPrefix = true
            var removeExtra = true
            var escapeWs = true
            var normalizerName = ""
            val r = ProtoReader(bytes, 0, bytes.size)
            while (r.hasMore()) {
                val tag = r.readVarint().toInt()
                val field = tag ushr 3
                val wire = tag and 7
                when {
                    field == 1 && wire == 2 -> pieces.add(readPiece(r.readBytesView()))
                    field == 2 && wire == 2 -> {
                        val ts = r.readBytesView()
                        while (ts.hasMore()) {
                            val t = ts.readVarint().toInt()
                            when (t ushr 3) {
                                3 -> modelType = if (ts.readVarint().toInt() == 2) ModelType.BPE else ModelType.UNIGRAM
                                40 -> unkId = ts.readVarint().toInt()
                                else -> ts.skip(t and 7)
                            }
                        }
                    }
                    field == 3 && wire == 2 -> {
                        val ns = r.readBytesView()
                        while (ns.hasMore()) {
                            val t = ns.readVarint().toInt()
                            when (t ushr 3) {
                                1 -> normalizerName = ns.readString()
                                3 -> addDummyPrefix = ns.readVarint() != 0L
                                4 -> removeExtra = ns.readVarint() != 0L
                                5 -> escapeWs = ns.readVarint() != 0L
                                else -> ns.skip(t and 7)
                            }
                        }
                    }
                    else -> r.skip(wire)
                }
            }
            // The UNKNOWN piece's index is authoritative when present.
            pieces.indexOfFirst { it.type == PieceType.UNKNOWN }.takeIf { it >= 0 }?.let { unkId = it }
            return SentencePieceModel(pieces, modelType, unkId, addDummyPrefix, removeExtra, escapeWs, normalizerName)
        }

        private fun readPiece(r: ProtoReader): Piece {
            var piece = ""
            var score = 0f
            var type = PieceType.NORMAL
            while (r.hasMore()) {
                val t = r.readVarint().toInt()
                when (t ushr 3) {
                    1 -> piece = r.readString()
                    2 -> score = r.readFloat()
                    3 -> type = when (r.readVarint().toInt()) {
                        2 -> PieceType.UNKNOWN; 3 -> PieceType.CONTROL; 4 -> PieceType.USER_DEFINED
                        5 -> PieceType.UNUSED; 6 -> PieceType.BYTE; else -> PieceType.NORMAL
                    }
                    else -> r.skip(t and 7)
                }
            }
            return Piece(piece, score, type)
        }
    }

    /** Tiny protobuf wire-format cursor. */
    private class ProtoReader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore() = pos < end

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun readFloat(): Float {
            val bits = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return Float.fromBits(bits)
        }

        fun readBytesView(): ProtoReader {
            val len = readVarint().toInt()
            val view = ProtoReader(buf, pos, pos + len)
            pos += len
            return view
        }

        fun readString(): String {
            val len = readVarint().toInt()
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> {
                    // NOTE: `pos += readVarint()` would read the old pos before the call advances it.
                    val len = readVarint().toInt()
                    pos += len
                }
                5 -> pos += 4
                else -> error("unsupported wire type $wire")
            }
        }
    }
}
