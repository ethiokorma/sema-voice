package com.semavoice.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.semavoice.core.common.Language
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {
    private lateinit var db: SemaDatabase
    private lateinit var notes: NotesRepository
    private lateinit var history: HistoryRepository
    private lateinit var dictionary: DictionaryRepository
    private lateinit var cipher: KeystoreTextCipher

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SemaDatabase::class.java).build()
        cipher = KeystoreTextCipher(CryptoBox("sema_test_key"))
        notes = NotesRepository(db, cipher, File(ctx.cacheDir, "test-notes"))
        history = HistoryRepository(db, cipher)
        dictionary = DictionaryRepository(db)
    }

    @After fun tearDown() { db.close() }

    @Test fun noteRoundTripIsEncryptedAtRest() = runBlocking {
        val note = VoiceNote(
            NotesRepository.newId(), "Test", 1L, 1L, 1200L, Language.AMHARIC, Language.ENGLISH,
            "እንዴት ነህ? ነገ እንገናኝ።", "How are you? Let's meet tomorrow.", false, null,
        )
        notes.save(note)
        val raw = db.notes().byId(note.id)!!
        assertNotEquals(note.transcript, raw.transcriptEnc)
        assertFalse(raw.transcriptEnc.contains("እንዴት"))
        val back = notes.byId(note.id)!!
        assertEquals(note.transcript, back.transcript)
        assertEquals(note.translation, back.translation)
        assertEquals(Language.AMHARIC, back.language)
    }

    @Test fun searchFindsAmharicTranscript() = runBlocking {
        notes.save(VoiceNote(NotesRepository.newId(), "a", 1, 1, 0, Language.AMHARIC, null, "ነገ ስብሰባ አለኝ", null, false, null))
        notes.save(VoiceNote(NotesRepository.newId(), "b", 2, 2, 0, Language.ENGLISH, null, "grocery list", null, false, null))
        assertEquals(1, notes.search("ስብሰባ").first().size)
        assertEquals(1, notes.search("GROCERY").first().size)
        assertEquals(0, notes.search("nothing").first().size)
    }

    @Test fun deleteAudioKeepsTranscript() = runBlocking {
        val id = NotesRepository.newId()
        notes.audioFile(id).apply { parentFile?.mkdirs(); writeBytes(ByteArray(16)) }
        notes.save(VoiceNote(id, "t", 1, 1, 0, Language.ENGLISH, null, "hello", null, true, null))
        notes.deleteAudioOnly(id)
        assertFalse(notes.audioFile(id).exists())
        assertEquals("hello", notes.byId(id)!!.transcript)
        assertFalse(notes.byId(id)!!.hasAudio)
        notes.delete(id)
        assertNull(notes.byId(id))
    }

    @Test fun historyFilters() = runBlocking {
        history.save(HistoryItem(HistoryRepository.newId(), 1, HistoryKind.TRANSCRIPTION, Language.AMHARIC, null, "ሰላም", null, false, null))
        history.save(HistoryItem(HistoryRepository.newId(), 2, HistoryKind.TRANSLATION, Language.AMHARIC, Language.ENGLISH, "ሰላም", "hello", true, null))
        assertEquals(2, history.observeAll().first().size)
        assertEquals(1, history.query("", HistoryKind.TRANSLATION, null, false).first().size)
        assertEquals(1, history.query("", null, null, true).first().size)
        assertEquals(2, history.query("", null, Language.AMHARIC, false).first().size)
        history.deleteAll()
        assertEquals(0, history.count())
    }

    @Test fun dictionaryImportExport() = runBlocking {
        val n = dictionary.importTsv("Tewodros\tTedros|Theodoros\ten\tNAME\nቦሌ\tቦሊ\tam\tPLACE\n")
        assertEquals(2, n)
        val all = dictionary.all()
        assertEquals(2, all.size)
        val tsv = dictionary.exportTsv()
        assertTrue(tsv.contains("Tewodros\tTedros|Theodoros\ten\tNAME"))
        assertEquals(1, dictionary.forLanguage(Language.AMHARIC).size)
    }
}
