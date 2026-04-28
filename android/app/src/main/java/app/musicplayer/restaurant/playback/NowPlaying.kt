package app.musicplayer.restaurant.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object NowPlaying {
    private val _trackName = MutableStateFlow<String?>(null)
    val trackName: StateFlow<String?> = _trackName

    fun set(name: String?) {
        _trackName.value = name?.removeSuffix(".mp3")
    }
}
