package io.github.verticalhorizon.timetracker.ui

import java.util.Locale

/** The names the tracker's display uses: T1… for trackers, C1… for countdowns. */
fun trackerLabel(index: Int) = "T${index + 1}"
fun countdownLabel(index: Int) = "C${index + 1}"

/** 1:24:10 */
fun clock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s % 3600 / 60, s % 60)
}

/** 4:12, or 1:30:00 from an hour up */
fun compactClock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) clock(s) else "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
}

/** 1h 24m, 5m */
fun hoursMinutes(seconds: Long): String {
    val m = seconds.coerceAtLeast(0) / 60
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

/** A countdown's length: 5 min, 1 h 30 min, 24 h */
fun duration(seconds: Long): String {
    val m = seconds.coerceAtLeast(0) / 60
    val h = m / 60
    val r = m % 60
    return when {
        h == 0L -> "$r min"
        r == 0L -> "$h h"
        else -> "$h h $r min"
    }
}
