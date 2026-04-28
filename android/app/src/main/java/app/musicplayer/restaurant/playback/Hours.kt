package app.musicplayer.restaurant.playback

import app.musicplayer.restaurant.data.HoursConfig
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One contiguous open window in the local timezone. Note `closeAt` may fall
 * on the next day when the day's hours cross midnight.
 */
data class Window(val openAt: ZonedDateTime, val closeAt: ZonedDateTime) {
    fun contains(now: ZonedDateTime): Boolean = !now.isBefore(openAt) && now.isBefore(closeAt)
}

object HoursLogic {

    private val dayKey = mapOf(
        DayOfWeek.MONDAY to "mon",
        DayOfWeek.TUESDAY to "tue",
        DayOfWeek.WEDNESDAY to "wed",
        DayOfWeek.THURSDAY to "thu",
        DayOfWeek.FRIDAY to "fri",
        DayOfWeek.SATURDAY to "sat",
        DayOfWeek.SUNDAY to "sun",
    )

    /** Build the open window for the given date in the config's timezone, or null if closed. */
    fun windowFor(date: LocalDate, config: HoursConfig): Window? {
        val day = config.schedule[dayKey[date.dayOfWeek]] ?: return null
        val zone = ZoneId.of(config.timezone)
        val start = LocalTime.parse(day.start)
        val end = LocalTime.parse(day.end)
        val open = date.atTime(start).atZone(zone)
        val close = if (end > start) {
            date.atTime(end).atZone(zone)
        } else {
            // end <= start means the window crosses into the next day
            date.plusDays(1).atTime(end).atZone(zone)
        }
        return Window(open, close)
    }

    fun isOpen(now: Instant, config: HoursConfig): Boolean {
        if (!config.enabled) return false
        val zone = ZoneId.of(config.timezone)
        val nowZ = now.atZone(zone)
        val today = nowZ.toLocalDate()
        // Check yesterday's window in case it crossed midnight, plus today's
        for (date in listOf(today.minusDays(1), today)) {
            val w = windowFor(date, config) ?: continue
            if (w.contains(nowZ)) return true
        }
        return false
    }

    /**
     * The next instant at which the open/closed state changes. Returns null
     * if the schedule is disabled or has no entries in the next two weeks.
     */
    fun nextTransition(now: Instant, config: HoursConfig): Instant? {
        if (!config.enabled) return null
        val zone = ZoneId.of(config.timezone)
        val nowZ = now.atZone(zone)
        val openNow = isOpen(now, config)
        val today = nowZ.toLocalDate()
        for (offset in -1..14L) {
            val w = windowFor(today.plusDays(offset), config) ?: continue
            if (openNow) {
                if (w.contains(nowZ)) return w.closeAt.toInstant()
            } else {
                if (w.openAt.isAfter(nowZ)) return w.openAt.toInstant()
            }
        }
        return null
    }

    /** Human-readable today's hours, e.g. "11:00 – 22:00" or "Closed". */
    fun describeToday(config: HoursConfig): String {
        if (!config.enabled) return "Disabled"
        val zone = ZoneId.of(config.timezone)
        val today = LocalDate.now(zone)
        val day = config.schedule[dayKey[today.dayOfWeek]] ?: return "Closed today"
        return "${day.start} – ${day.end}"
    }
}
