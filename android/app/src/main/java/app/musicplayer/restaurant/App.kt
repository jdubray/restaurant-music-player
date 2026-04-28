package app.musicplayer.restaurant

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import app.musicplayer.restaurant.playback.PlaybackService
import app.musicplayer.restaurant.sync.SyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncWorker.scheduleDaily(this)
        SyncWorker.runNow(this)
        startPlayback()
    }

    private fun startPlayback() {
        val svc = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, svc)
        } else {
            startService(svc)
        }
    }
}
