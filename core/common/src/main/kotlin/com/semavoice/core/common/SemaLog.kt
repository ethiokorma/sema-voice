package com.semavoice.core.common

import android.util.Log

/**
 * Privacy-aware logger. Never pass dictated text, transcripts or translations to this
 * logger. It only exists for engine lifecycle / error diagnostics.
 */
object SemaLog {
    @Volatile var enabled: Boolean = true
    fun d(tag: String, msg: String) { if (enabled) Log.d("Sema/$tag", msg) }
    fun i(tag: String, msg: String) { if (enabled) Log.i("Sema/$tag", msg) }
    fun w(tag: String, msg: String, t: Throwable? = null) { if (enabled) Log.w("Sema/$tag", msg, t) }
    fun e(tag: String, msg: String, t: Throwable? = null) { if (enabled) Log.e("Sema/$tag", msg, t) }
}
