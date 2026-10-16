package com.semavoice.core.runtime

import android.content.Context
import com.semavoice.core.audio.AudioRecorder
import com.semavoice.core.common.DefaultDispatcherProvider
import com.semavoice.core.common.DispatcherProvider
import com.semavoice.core.database.DatabaseFactory
import com.semavoice.core.database.DictionaryRepository
import com.semavoice.core.database.HistoryRepository
import com.semavoice.core.database.KeystoreTextCipher
import com.semavoice.core.database.NotesRepository
import com.semavoice.core.database.SemaDatabase
import com.semavoice.core.models.ModelManager
import com.semavoice.core.settings.SettingsRepository
import com.semavoice.core.speech.WhisperEngine
import com.semavoice.core.translation.OnnxSeq2SeqTranslator
import com.semavoice.core.tts.AndroidSystemTts
import com.semavoice.core.writing.RuleBasedWritingAssistant
import java.io.File

/**
 * Process-wide object graph. Both the app process and the InputMethodService (same
 * process) share one instance so a model loaded by the keyboard is reused by the app and
 * vice versa. Deliberately a plain singleton: no DI framework to keep IME startup fast.
 */
object SemaGraph {
    @Volatile private var initialized = false
    lateinit var appContext: Context private set
    lateinit var dispatchers: DispatcherProvider private set
    lateinit var settings: SettingsRepository private set
    lateinit var modelManager: ModelManager private set
    lateinit var speech: WhisperEngine private set
    /** CTC acoustic models (w2v-BERT) via ONNX Runtime; used for models with format "onnx-ctc". */
    lateinit var ctc: com.semavoice.core.speech.OnnxCtcEngine private set
    lateinit var translator: OnnxSeq2SeqTranslator private set
    lateinit var writing: RuleBasedWritingAssistant private set
    lateinit var database: SemaDatabase private set
    lateinit var notes: NotesRepository private set
    lateinit var history: HistoryRepository private set
    lateinit var dictionary: DictionaryRepository private set
    lateinit var tts: AndroidSystemTts private set
    lateinit var pipeline: DictationPipeline private set
    lateinit var voiceStudio: VoiceStudioClient private set

    fun newRecorder(): AudioRecorder = AudioRecorder(appContext, dispatchers)

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        dispatchers = DefaultDispatcherProvider()
        settings = SettingsRepository(appContext)
        modelManager = ModelManager(appContext)
        speech = WhisperEngine(dispatchers)
        ctc = com.semavoice.core.speech.OnnxCtcEngine(dispatchers)
        translator = OnnxSeq2SeqTranslator(dispatchers)
        writing = RuleBasedWritingAssistant()
        database = DatabaseFactory.create(appContext)
        val cipher = KeystoreTextCipher()
        notes = NotesRepository(database, cipher, File(appContext.filesDir, "notes"))
        history = HistoryRepository(database, cipher)
        dictionary = DictionaryRepository(database)
        tts = AndroidSystemTts(appContext)
        voiceStudio = VoiceStudioClient(settings)
        pipeline = DictationPipeline(this)
        initialized = true
    }

    val isInitialized: Boolean get() = initialized
}
