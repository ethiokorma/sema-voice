package com.semavoice.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.semavoice.core.runtime.SemaGraph

class SemaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SemaGraph.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(RECORDING_CHANNEL, getString(R.string.notif_channel_recording), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.notif_channel_recording_desc)
                },
            )
        }
    }

    companion object { const val RECORDING_CHANNEL = "recording" }
}
