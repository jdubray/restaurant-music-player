package app.musicplayer.restaurant.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.musicplayer.restaurant.MainActivity
import app.musicplayer.restaurant.R
import app.musicplayer.restaurant.data.HoursConfig
import app.musicplayer.restaurant.data.Settings
import app.musicplayer.restaurant.data.UserOverride
import app.musicplayer.restaurant.sync.Syncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Media3 service that owns the ExoPlayer and reconciles playback state
 * against:
 *   1. The cached business-hours schedule from the server.
 *   2. A short-lived user override (manual play / pause from the app).
 *
 * Reconciliation runs whenever any of those inputs change, plus a 60s
 * background tick so we cross hours boundaries without waiting for input.
 */
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private var latestInput: ReconcileInput = ReconcileInput.empty
    private var lastSeenSyncAt: Long = -1L

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                shuffleModeEnabled = true
                repeatMode = Player.REPEAT_MODE_ALL
            }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                NowPlaying.set(mediaItem?.mediaId?.takeIf { it.isNotEmpty() })
            }
        })
        session = MediaSession.Builder(this, player).build()
        loadPlaylist()
        startReconciler()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Cold-start may happen during off-hours when the player is paused.
        // Media3 only auto-promotes to foreground once playback begins, so we
        // post our own placeholder notification immediately to satisfy the
        // 5-second startForeground requirement on Android 8+. Once the player
        // actually starts, Media3 takes over the notification.
        ensureForeground()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the launcher activity is swiped away
    }

    private fun ensureForeground() {
        val channelId = ensureChannel()
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingTap = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Ready")
            .setContentIntent(pendingTap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun ensureChannel(): String {
        val id = CHANNEL_ID
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(id) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(id, getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.notification_channel_playback_desc)
                    setShowBadge(false)
                }
            )
        }
        return id
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        player.release()
        super.onDestroy()
    }

    private fun loadPlaylist() {
        val files = Syncer.musicDir(this).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".mp3", ignoreCase = true) }
        val wasPlaying = player.isPlaying
        if (files.isEmpty()) {
            player.clearMediaItems()
            NowPlaying.set(null)
            return
        }
        val items = files.shuffled().map {
            MediaItem.Builder().setUri(it.toUri()).setMediaId(it.name).build()
        }
        player.setMediaItems(items)
        player.prepare()
        if (wasPlaying) player.play()
    }

    private fun startReconciler() {
        val settings = Settings(applicationContext)

        // React to override / hours / sync-completion changes
        scope.launch {
            combine(
                settings.userOverride,
                settings.userOverrideUntilMs,
                settings.hoursJson,
                settings.lastSyncAtMs,
            ) { override, until, hours, syncAt -> ReconcileInput(override, until, hours, syncAt) }
                .distinctUntilChanged()
                .collectLatest { input ->
                    latestInput = input
                    reconcile(input)
                }
        }

        // Cross hours boundaries even if nothing else changes
        scope.launch {
            while (isActive) {
                delay(60_000)
                reconcile(latestInput)
            }
        }
    }

    private fun reconcile(input: ReconcileInput) {
        // Reload the playlist whenever a sync just completed
        if (input.lastSyncAt != lastSeenSyncAt) {
            lastSeenSyncAt = input.lastSyncAt
            loadPlaylist()
        }

        val now = Instant.now()
        val config = input.hoursJson?.let {
            runCatching { json.decodeFromString(HoursConfig.serializer(), it) }.getOrNull()
        }

        // Expire stale overrides at their scheduled boundary
        var override = input.override
        if (override != UserOverride.NONE && input.untilMs in 1..now.toEpochMilli()) {
            scope.launch { Settings(applicationContext).setUserOverride(UserOverride.NONE, 0L) }
            override = UserOverride.NONE
        }

        val openByHours = config?.let { HoursLogic.isOpen(now, it) } ?: false
        val shouldPlay = when (override) {
            UserOverride.PLAY -> true
            UserOverride.PAUSE -> false
            UserOverride.NONE -> openByHours
        }

        if (shouldPlay && !player.isPlaying) {
            if (player.mediaItemCount == 0) loadPlaylist()
            if (player.mediaItemCount > 0) player.play()
        } else if (!shouldPlay && player.isPlaying) {
            player.pause()
        }
    }

    private data class ReconcileInput(
        val override: UserOverride,
        val untilMs: Long,
        val hoursJson: String?,
        val lastSyncAt: Long,
    ) {
        companion object {
            val empty = ReconcileInput(UserOverride.NONE, 0L, null, 0L)
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "music_playback"
    }
}
