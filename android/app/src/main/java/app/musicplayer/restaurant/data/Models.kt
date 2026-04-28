package app.musicplayer.restaurant.data

import kotlinx.serialization.Serializable

@Serializable
data class TrackInfo(
    val name: String,
    val size: Long,
    val sha1: String,
)

@Serializable
data class TrackList(
    val tracks: List<TrackInfo>,
    val generated_at: String? = null,
)

@Serializable
data class DayHours(
    val start: String,
    val end: String,
)

@Serializable
data class HoursConfig(
    val timezone: String,
    val enabled: Boolean,
    val schedule: Map<String, DayHours?>,
)

enum class UserOverride { NONE, PLAY, PAUSE }
