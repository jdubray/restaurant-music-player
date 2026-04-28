package app.musicplayer.restaurant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.musicplayer.restaurant.data.HoursConfig
import app.musicplayer.restaurant.data.Settings
import app.musicplayer.restaurant.data.UserOverride
import app.musicplayer.restaurant.playback.HoursLogic
import app.musicplayer.restaurant.playback.NowPlaying
import app.musicplayer.restaurant.sync.SyncWorker
import app.musicplayer.restaurant.sync.Syncer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.text.DateFormat
import java.time.Instant
import java.util.Date

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        viewModel = viewModel,
                        onRequestBatteryUnoptimized = ::requestIgnoreBatteryOptimization,
                    )
                }
            }
        }
        ensureNotificationPermission()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op; the service will start regardless and the channel still exists */ }

    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

data class UiState(
    val statusText: String = "Starting…",
    val isPlaying: Boolean = false,
    val serverUrl: String = Settings.DEFAULT_URL,
    val urlDraft: String = Settings.DEFAULT_URL,
    val todayHoursText: String = "—",
    val trackCount: Int = 0,
    val lastSyncText: String = "Never",
)

class MainViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val json = Json { ignoreUnknownKeys = true }

    val trackName: StateFlow<String?> = NowPlaying.trackName

    val uiState = combine(
        settings.serverUrl,
        settings.userOverride,
        settings.hoursJson,
        settings.lastSyncAtMs,
        settings.lastSyncMessage,
    ) { url, override, hours, syncAt, syncMsg ->
        val config = hours?.let { runCatching { json.decodeFromString(HoursConfig.serializer(), it) }.getOrNull() }
        val openByHours = config?.let { HoursLogic.isOpen(Instant.now(), it) } ?: false
        val isPlaying = when (override) {
            UserOverride.PLAY -> true
            UserOverride.PAUSE -> false
            UserOverride.NONE -> openByHours
        }
        val status = when {
            override == UserOverride.PLAY -> "Playing (manual override)"
            override == UserOverride.PAUSE -> "Paused (manual override)"
            openByHours -> "Playing"
            config == null -> "Awaiting server…"
            else -> "Off-hours"
        }
        val today = config?.let { HoursLogic.describeToday(it) } ?: "—"
        val tracks = Syncer.musicDir(getApplication()).listFiles().orEmpty()
            .count { it.isFile && it.name.endsWith(".mp3", ignoreCase = true) }
        val syncText = if (syncAt == 0L) "Never" else "${formatTime(syncAt)} – $syncMsg"
        UiState(
            statusText = status,
            isPlaying = isPlaying,
            serverUrl = url,
            urlDraft = url,
            todayHoursText = today,
            trackCount = tracks,
            lastSyncText = syncText,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    fun togglePlayPause() {
        viewModelScope.launch {
            val current = uiState.value
            val hoursJson = settings.hoursJson.first()
            val config = hoursJson?.let {
                runCatching { json.decodeFromString(HoursConfig.serializer(), it) }.getOrNull()
            }
            val nextBoundary = config?.let { HoursLogic.nextTransition(Instant.now(), it) }?.toEpochMilli()
                ?: (System.currentTimeMillis() + 24L * 60 * 60 * 1000)
            val newOverride = if (current.isPlaying) UserOverride.PAUSE else UserOverride.PLAY
            settings.setUserOverride(newOverride, nextBoundary)
        }
    }

    fun setUrlDraft(url: String) {
        viewModelScope.launch { settings.setServerUrl(url) }
    }

    fun syncNow() {
        SyncWorker.runNow(getApplication())
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel, onRequestBatteryUnoptimized: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val trackName by viewModel.trackName.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Restaurant Music", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.statusText, style = MaterialTheme.typography.titleLarge)
                if (state.isPlaying && trackName != null) {
                    Text("Now: $trackName", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Today: ${state.todayHoursText}", color = Color(0xFFAAAAAA))
                Text("${state.trackCount} tracks loaded", color = Color(0xFFAAAAAA))
            }
        }

        Button(
            onClick = viewModel::togglePlayPause,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
            )
            Spacer(Modifier.height(0.dp))
            Text(
                text = if (state.isPlaying) "  Pause" else "  Play",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sync", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = viewModel::setUrlDraft,
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = viewModel::syncNow) { Text("Sync now") }
                    Text(
                        "Last sync: ${state.lastSyncText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFAAAAAA),
                    )
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reliability", style = MaterialTheme.typography.titleMedium)
                Text(
                    "For continuous playback, exempt this app from battery optimization.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAAAAA),
                )
                OutlinedButton(onClick = onRequestBatteryUnoptimized) { Text("Open battery settings") }
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
