package org.nostr.nostrord.utils

expect fun epochMillis(): Long

fun epochSeconds(): Long = epochMillis() / 1000

data class SimpleDateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

expect fun timestampToDateTime(epochSeconds: Long): SimpleDateTime

fun getDateLabel(timestamp: Long): String {
    val now = epochSeconds()
    val nowDateTime = timestampToDateTime(now)
    val messageDateTime = timestampToDateTime(timestamp)

    val daysDiff = calculateDaysDiff(nowDateTime, messageDateTime)
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val monthName = monthNames.getOrNull(messageDateTime.month - 1) ?: messageDateTime.month.toString()

    return when {
        daysDiff == 0 -> "Today"
        daysDiff == 1 -> "Yesterday"
        messageDateTime.year != nowDateTime.year -> {
            "${messageDateTime.day} $monthName ${messageDateTime.year}"
        }
        else -> {
            "${messageDateTime.day} $monthName"
        }
    }
}

/** Date label plus clock time, e.g. "Today 11:02", "23 May 11:02", "23 May 2025 11:02". */
fun formatDateTime(timestamp: Long): String {
    val dateTime = timestampToDateTime(timestamp)
    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')
    return "${getDateLabel(timestamp)} $hour:$minute"
}

fun formatTime(timestamp: Long): String {
    val now = epochSeconds()
    val nowDateTime = timestampToDateTime(now)
    val dateTime = timestampToDateTime(timestamp)
    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')

    return if (dateTime.year != nowDateTime.year) {
        // Show date and year for messages from previous years
        val day = dateTime.day.toString().padStart(2, '0')
        val month = dateTime.month.toString().padStart(2, '0')
        "$day/$month/${dateTime.year} $hour:$minute"
    } else {
        "$hour:$minute"
    }
}

fun formatTimestamp(timestamp: Long): String {
    val now = epochSeconds()
    val diff = now - timestamp

    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        else -> "${diff / 86400}d ago"
    }
}

/**
 * Days since 1970-01-01 for a civil date (proleptic Gregorian), by Howard Hinnant's days_from_civil.
 * Calendar-exact: month lengths and leap years both count, which a fixed 30-day month does not.
 */
internal fun daysFromCivil(
    year: Int,
    month: Int,
    day: Int,
): Long {
    // March-based year: the leap day lands last, so no month length depends on the leap rule.
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val monthFromMarch = (month + 9) % 12
    val dayOfYear = (153 * monthFromMarch + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365L + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}

private fun calculateDaysDiff(
    date1: SimpleDateTime,
    date2: SimpleDateTime,
): Int = kotlin.math.abs(
    daysFromCivil(date1.year, date1.month, date1.day) - daysFromCivil(date2.year, date2.month, date2.day),
).toInt()
