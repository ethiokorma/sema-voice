// JNI bridge between com.semavoice.core.speech.WhisperNative and whisper.cpp.
//
// Threading contract: a whisper_context must not be used from two threads at once.
// The Kotlin side serialises all calls through a single inference dispatcher; the only
// cross-thread call allowed here is cancel(), which flips an atomic flag read by the
// abort callback.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "SemaWhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct SemaContext {
    whisper_context* ctx = nullptr;
    std::atomic<bool> abort{false};
    std::atomic<int> progress{0};
};

inline SemaContext* handle(jlong h) { return reinterpret_cast<SemaContext*>(h); }

bool abort_callback(void* user_data) {
    auto* sc = static_cast<SemaContext*>(user_data);
    return sc->abort.load(std::memory_order_relaxed);
}

void progress_callback(whisper_context*, whisper_state*, int progress, void* user_data) {
    static_cast<SemaContext*>(user_data)->progress.store(progress, std::memory_order_relaxed);
}

void whisper_log_to_android(ggml_log_level level, const char* text, void*) {
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN: prio = ANDROID_LOG_WARN; break;
        case GGML_LOG_LEVEL_INFO: prio = ANDROID_LOG_INFO; break;
        default: break;
    }
    // whisper.cpp only logs model/loader diagnostics here; never transcript content.
    __android_log_write(prio, "whisper.cpp", text);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeInit(JNIEnv* env, jobject, jstring jpath) {
    whisper_log_set(whisper_log_to_android, nullptr);
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed");
        return 0;
    }
    auto* sc = new SemaContext();
    sc->ctx = ctx;
    return reinterpret_cast<jlong>(sc);
}

JNIEXPORT void JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeFree(JNIEnv*, jobject, jlong h) {
    auto* sc = handle(h);
    if (sc == nullptr) return;
    if (sc->ctx != nullptr) whisper_free(sc->ctx);
    delete sc;
}

JNIEXPORT jboolean JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeIsMultilingual(JNIEnv*, jobject, jlong h) {
    auto* sc = handle(h);
    return (sc != nullptr && sc->ctx != nullptr && whisper_is_multilingual(sc->ctx)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeLangId(JNIEnv* env, jobject, jstring jlang) {
    const char* lang = env->GetStringUTFChars(jlang, nullptr);
    int id = whisper_lang_id(lang);
    env->ReleaseStringUTFChars(jlang, lang);
    return id;
}

/**
 * Runs full inference. Returns 0 on success, 1 when aborted, negative whisper error codes otherwise.
 * language: BCP-47-ish whisper code ("am", "en") or "auto".
 */
JNIEXPORT jint JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeTranscribe(
        JNIEnv* env, jobject, jlong h, jfloatArray jsamples, jstring jlanguage,
        jboolean translateToEnglish, jint threads, jstring jprompt, jboolean singleSegment) {
    auto* sc = handle(h);
    if (sc == nullptr || sc->ctx == nullptr) return -100;
    sc->abort.store(false);
    sc->progress.store(0);

    jsize n = env->GetArrayLength(jsamples);
    if (n <= 0) return -101;
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jsamples, 0, n, samples.data());

    const char* language = env->GetStringUTFChars(jlanguage, nullptr);
    std::string lang(language);
    env->ReleaseStringUTFChars(jlanguage, language);

    std::string prompt;
    if (jprompt != nullptr) {
        const char* p = env->GetStringUTFChars(jprompt, nullptr);
        prompt = p;
        env->ReleaseStringUTFChars(jprompt, p);
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = translateToEnglish == JNI_TRUE;
    params.n_threads = threads > 0 ? threads : 4;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = singleSegment == JNI_TRUE;
    params.suppress_blank = true;
    params.suppress_nst = true;          // no non-speech tokens like [MUSIC]
    params.temperature_inc = 0.2f;        // fallback decoding on low confidence
    params.no_speech_thold = 0.6f;
    params.abort_callback = abort_callback;
    params.abort_callback_user_data = sc;
    params.progress_callback = progress_callback;
    params.progress_callback_user_data = sc;
    if (lang == "auto" || lang.empty()) {
        params.language = "auto";
        params.detect_language = false;   // detect AND transcribe
    } else {
        params.language = lang.c_str();
    }
    if (!prompt.empty()) params.initial_prompt = prompt.c_str();

    int rc = whisper_full(sc->ctx, params, samples.data(), n);
    if (sc->abort.load()) return 1;
    return rc;
}

JNIEXPORT void JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeCancel(JNIEnv*, jobject, jlong h) {
    auto* sc = handle(h);
    if (sc != nullptr) sc->abort.store(true);
}

JNIEXPORT jint JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeProgress(JNIEnv*, jobject, jlong h) {
    auto* sc = handle(h);
    return sc == nullptr ? 0 : sc->progress.load();
}

JNIEXPORT jint JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeSegmentCount(JNIEnv*, jobject, jlong h) {
    auto* sc = handle(h);
    return (sc == nullptr || sc->ctx == nullptr) ? 0 : whisper_full_n_segments(sc->ctx);
}

JNIEXPORT jstring JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeSegmentText(JNIEnv* env, jobject, jlong h, jint i) {
    auto* sc = handle(h);
    if (sc == nullptr || sc->ctx == nullptr) return env->NewStringUTF("");
    const char* text = whisper_full_get_segment_text(sc->ctx, i);
    // whisper emits UTF-8; NewStringUTF expects modified UTF-8, which is identical for all
    // characters outside the supplementary planes. Ge'ez (U+1200..U+139F) is in the BMP.
    // For safety with 4-byte sequences (emoji), route through a byte array + String(UTF_8).
    jsize len = static_cast<jsize>(strlen(text));
    jbyteArray bytes = env->NewByteArray(len);
    env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte*>(text));
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(stringClass, ctor, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringClass);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeSegmentT0(JNIEnv*, jobject, jlong h, jint i) {
    auto* sc = handle(h);
    return (sc == nullptr || sc->ctx == nullptr) ? 0 : whisper_full_get_segment_t0(sc->ctx, i) * 10; // centiseconds -> ms
}

JNIEXPORT jlong JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeSegmentT1(JNIEnv*, jobject, jlong h, jint i) {
    auto* sc = handle(h);
    return (sc == nullptr || sc->ctx == nullptr) ? 0 : whisper_full_get_segment_t1(sc->ctx, i) * 10;
}

JNIEXPORT jstring JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeDetectedLanguage(JNIEnv* env, jobject, jlong h) {
    auto* sc = handle(h);
    if (sc == nullptr || sc->ctx == nullptr) return env->NewStringUTF("");
    int id = whisper_full_lang_id(sc->ctx);
    const char* s = id >= 0 ? whisper_lang_str(id) : "";
    return env->NewStringUTF(s == nullptr ? "" : s);
}

JNIEXPORT jstring JNICALL
Java_com_semavoice_core_speech_WhisperNative_nativeSystemInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
