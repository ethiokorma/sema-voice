package com.semavoice.core.database

import android.content.Context
import androidx.room.Room
import com.semavoice.core.common.Language
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

data class VoiceNote(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val durationMs: Long,
    val language: Language,
    val translationLanguage: Language?,
    val transcript: String,
    val translation: String?,
    val hasAudio: Boolean,
    val segmentsJson: String?,
)

enum class HistoryKind { TRANSCRIPTION, TRANSLATION }

data class HistoryItem(
    val id: String,
    val createdAt: Long,
    val kind: HistoryKind,
    val sourceLanguage: Language,
    val targetLanguage: Language?,
    val original: String,
    val result: String?,
    val favorite: Boolean,
    val title: String?,
)

enum class DictionaryCategory { NAME, PLACE, BUSINESS, TECH, OTHER }

data class DictionaryEntry(
    val id: String,
    val term: String,
    val variants: List<String>,
    val language: Language,
    val category: DictionaryCategory,
    val createdAt: Long,
    val useCount: Int,
)

object DatabaseFactory {
    fun create(context: Context): SemaDatabase =
        Room.databaseBuilder(context.applicationContext, SemaDatabase::class.java, "sema.db")
            .fallbackToDestructiveMigration(false)
            .build()
}

private fun searchIndexOf(vararg parts: String?): String =
    parts.filterNotNull().joinToString(" ").lowercase().take(4000)

class NotesRepository(private val db: SemaDatabase, private val cipher: TextCipher, private val audioDir: File) {
    init { audioDir.mkdirs() }

    private fun NoteEntity.toModel() = VoiceNote(
        id, title, createdAt, updatedAt, durationMs, Language.fromCode(language),
        translationLanguage?.let(Language::fromCode), cipher.decrypt(transcriptEnc),
        translationEnc?.let(cipher::decrypt), hasAudio, segmentsJson,
    )

    private fun VoiceNote.toEntity(searchable: Boolean) = NoteEntity(
        id, title, createdAt, updatedAt, durationMs, language.code, translationLanguage?.code,
        cipher.encrypt(transcript), translation?.let(cipher::encrypt),
        if (searchable) searchIndexOf(title, transcript, translation) else title.lowercase(),
        hasAudio, segmentsJson,
    )

    fun observeAll(): Flow<List<VoiceNote>> = db.notes().observeAll().map { l -> l.map { it.toModel() } }
    fun observeRecent(limit: Int): Flow<List<VoiceNote>> = db.notes().observeRecent(limit).map { l -> l.map { it.toModel() } }
    fun search(q: String): Flow<List<VoiceNote>> = db.notes().search(q.lowercase()).map { l -> l.map { it.toModel() } }
    suspend fun byId(id: String): VoiceNote? = db.notes().byId(id)?.toModel()
    suspend fun count() = db.notes().count()

    suspend fun save(note: VoiceNote, searchable: Boolean = true) = db.notes().upsert(note.toEntity(searchable))

    fun audioFile(id: String): File = File(audioDir, "$id.wav")

    suspend fun deleteAudioOnly(id: String) {
        audioFile(id).delete()
        db.notes().byId(id)?.let { db.notes().update(it.copy(hasAudio = false, updatedAt = System.currentTimeMillis())) }
    }

    suspend fun delete(id: String) {
        audioFile(id).delete()
        db.notes().byId(id)?.let { db.notes().delete(it) }
    }

    suspend fun deleteAll() {
        audioDir.listFiles()?.forEach { it.delete() }
        db.notes().deleteAll()
    }

    companion object { fun newId(): String = UUID.randomUUID().toString() }
}

class HistoryRepository(private val db: SemaDatabase, private val cipher: TextCipher) {
    private fun HistoryEntity.toModel() = HistoryItem(
        id, createdAt, HistoryKind.valueOf(kind), Language.fromCode(sourceLanguage),
        targetLanguage?.let(Language::fromCode), cipher.decrypt(originalEnc), resultEnc?.let(cipher::decrypt), favorite, title,
    )

    private fun HistoryItem.toEntity() = HistoryEntity(
        id, createdAt, kind.name, sourceLanguage.code, targetLanguage?.code,
        cipher.encrypt(original), result?.let(cipher::encrypt), searchIndexOf(title, original, result), favorite, title,
    )

    fun observeAll(): Flow<List<HistoryItem>> = db.history().observeAll().map { l -> l.map { it.toModel() } }
    fun query(q: String, kind: HistoryKind?, language: Language?, favoritesOnly: Boolean): Flow<List<HistoryItem>> =
        db.history().query(q.lowercase(), kind?.name, language?.code, if (favoritesOnly) 1 else 0).map { l -> l.map { it.toModel() } }

    suspend fun byId(id: String): HistoryItem? = db.history().byId(id)?.toModel()
    suspend fun save(item: HistoryItem) = db.history().upsert(item.toEntity())
    suspend fun delete(id: String) { db.history().byId(id)?.let { db.history().delete(it) } }
    suspend fun deleteAll() = db.history().deleteAll()
    suspend fun count() = db.history().count()

    companion object { fun newId(): String = UUID.randomUUID().toString() }
}

class DictionaryRepository(private val db: SemaDatabase) {
    private fun DictionaryEntity.toModel() = DictionaryEntry(
        id, term, variants.split('|').filter { it.isNotBlank() }, Language.fromCode(language),
        runCatching { DictionaryCategory.valueOf(category) }.getOrDefault(DictionaryCategory.OTHER), createdAt, useCount,
    )

    private fun DictionaryEntry.toEntity() = DictionaryEntity(
        id, term, variants.joinToString("|"), language.code, category.name, createdAt, useCount,
    )

    fun observeAll(): Flow<List<DictionaryEntry>> = db.dictionary().observeAll().map { l -> l.map { it.toModel() } }
    suspend fun all(): List<DictionaryEntry> = db.dictionary().all().map { it.toModel() }
    suspend fun forLanguage(language: Language): List<DictionaryEntry> = db.dictionary().forLanguage(language.code).map { it.toModel() }
    suspend fun save(entry: DictionaryEntry) = db.dictionary().upsert(entry.toEntity())
    suspend fun saveAll(entries: List<DictionaryEntry>) = db.dictionary().upsertAll(entries.map { it.toEntity() })
    suspend fun delete(entry: DictionaryEntry) = db.dictionary().delete(entry.toEntity())
    suspend fun deleteAll() = db.dictionary().deleteAll()
    suspend fun bump(id: String) = db.dictionary().bump(id)

    /** Export as simple TSV: term, variants(|), language, category. */
    suspend fun exportTsv(): String = all().joinToString("\n") { e ->
        listOf(e.term, e.variants.joinToString("|"), e.language.code, e.category.name).joinToString("\t")
    }

    suspend fun importTsv(text: String): Int {
        val entries = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val cols = line.split('\t')
            if (cols.isEmpty() || cols[0].isBlank()) return@mapNotNull null
            DictionaryEntry(
                UUID.randomUUID().toString(), cols[0].trim(),
                cols.getOrNull(1)?.split('|')?.filter { it.isNotBlank() } ?: emptyList(),
                Language.fromCode(cols.getOrNull(2)),
                runCatching { DictionaryCategory.valueOf(cols.getOrNull(3) ?: "") }.getOrDefault(DictionaryCategory.OTHER),
                System.currentTimeMillis(), 0,
            )
        }.toList()
        saveAll(entries)
        return entries.size
    }

    companion object { fun newId(): String = UUID.randomUUID().toString() }
}
