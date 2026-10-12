package com.semavoice.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE search_index LIKE '%' || :q || '%' OR title LIKE '%' || :q || '%' ORDER BY updatedAt DESC")
    fun search(q: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: String): NoteEntity?

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Update suspend fun update(note: NoteEntity)
    @Delete suspend fun delete(note: NoteEntity)
    @Query("DELETE FROM notes") suspend fun deleteAll()
    @Query("SELECT COUNT(*) FROM notes") suspend fun count(): Int
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE (:kind IS NULL OR kind = :kind) AND (:lang IS NULL OR sourceLanguage = :lang OR targetLanguage = :lang) AND (:favOnly = 0 OR favorite = 1) AND (search_index LIKE '%' || :q || '%' OR title LIKE '%' || :q || '%') ORDER BY createdAt DESC")
    fun query(q: String, kind: String?, lang: String?, favOnly: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id") suspend fun byId(id: String): HistoryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: HistoryEntity)
    @Update suspend fun update(item: HistoryEntity)
    @Delete suspend fun delete(item: HistoryEntity)
    @Query("DELETE FROM history") suspend fun deleteAll()
    @Query("SELECT COUNT(*) FROM history") suspend fun count(): Int
}

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dictionary ORDER BY useCount DESC, term ASC")
    fun observeAll(): Flow<List<DictionaryEntity>>

    @Query("SELECT * FROM dictionary ORDER BY useCount DESC, term ASC")
    suspend fun all(): List<DictionaryEntity>

    @Query("SELECT * FROM dictionary WHERE language = :lang OR language = 'auto' ORDER BY useCount DESC")
    suspend fun forLanguage(lang: String): List<DictionaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: DictionaryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(entries: List<DictionaryEntity>)
    @Delete suspend fun delete(entry: DictionaryEntity)
    @Query("DELETE FROM dictionary") suspend fun deleteAll()
    @Query("UPDATE dictionary SET useCount = useCount + 1 WHERE id = :id") suspend fun bump(id: String)
}

@Database(entities = [NoteEntity::class, HistoryEntity::class, DictionaryEntity::class], version = 1, exportSchema = true)
abstract class SemaDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun history(): HistoryDao
    abstract fun dictionary(): DictionaryDao
}
