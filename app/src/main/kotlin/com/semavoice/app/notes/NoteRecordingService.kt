package com.semavoice.app.notes

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.semavoice.app.MainActivity
import com.semavoice.app.R
import com.semavoice.app.SemaApplication
import com.semavoice.core.audio.AudioRecorder
import com.semavoice.core.audio.RecordingConfig
import com.semavoice.core.audio.RecordingState
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.PcmAudio
import com.semavoice.core.runtime.SemaGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service (type microphone) for long voice notes so recording survives the
 * app being backgrounded. It only exists while the user has explicitly started a note
 * and shows a persistent notification the whole time (section 16: visible recording).
 */
class NoteRecordingService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@NoteRecordingService }
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val recorder: AudioRecorder by lazy { SemaGraph.newRecorder() }
    val state: StateFlow<RecordingState> get() = recorder.state

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    suspend fun start(config: RecordingConfig): Outcome<Unit> = recorder.start(config)
    fun pause() = recorder.pause()
    fun resume() = recorder.resume()
    suspend fun stop(): Outcome<PcmAudio> { val r = recorder.stop(); finish(); return r }
    suspend fun cancel() { recorder.cancel(); finish() }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("route", "notes"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, SemaApplication.RECORDING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_recording_title))
            .setContentText(getString(R.string.notif_recording_text))
            .setOngoing(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        recorder.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        fun startIntent(ctx: Context) = Intent(ctx, NoteRecordingService::class.java)
    }
}
