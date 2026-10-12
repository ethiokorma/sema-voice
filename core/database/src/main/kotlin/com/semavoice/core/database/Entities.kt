package com.semavoice.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A voice note. [transcriptEnc] / [translationEnc] hold Keystore-encrypted text; the
 * optional audio file lives in filesDir/notes/<id>.wav and is deleted independently.
 */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val durationMs: Long,
    val language: String,
    val translationLanguage: String?,
    @ColumnInfo(name = "transcript_enc") val transcriptEnc: String,
    @ColumnInfo(name = "translation_enc") val translationEnc: String?,
    /** Lower-cased, unencrypted search index of the transcript, only when the user allows search. */
    @ColumnInfo(name = "search_index") val searchIndex: String,
    val hasAudio: Boolean,
    val segmentsJson: String?,
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    /** "TRANSCRIPTION" or "TRANSLATION" */
    val kind: String,
    val sourceLanguage: String,
    val targetLanguage: String?,
    @ColumnInfo(name = "original_enc") val originalEnc: String,
    @ColumnInfo(name = "result_enc") val resultEnc: String?,
    @ColumnInfo(name = "search_index") val searchIndex: String,
    val favorite: Boolean,
    val title: String?,
)

@Entity(tableName = "dictionary")
data class DictionaryEntity(
    @PrimaryKey val id: String,
    /** Preferred spelling, e.g. "Addis Ababa" or "ጤና ይስጥልኝ". */
    val term: String,
    /** Pipe-separated misrecognitions that should map to [term]. */
    val variants: String,
    val language: String,
    /** "NAME", "PLACE", "BUSINESS", "TECH", "OTHER" */
    val category: String,
    val createdAt: Long,
    val useCount: Int,
)
