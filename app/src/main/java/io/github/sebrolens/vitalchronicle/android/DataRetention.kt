package io.github.sebrolens.vitalchronicle.android

import java.time.LocalDate

/**
 * Local archive retention policy.
 *
 * Daily/low-volume health history is kept for at most 90 days. Raw heart-rate
 * samples are deliberately current-day-only because this is by far the densest
 * stream used by the Android dashboard. Other high-volume cardiac streams keep
 * a shorter 15-day archive.
 */
object DataRetention {
    const val GENERAL_DAYS = 90
    const val HIGH_VOLUME_CARDIAC_DAYS = 15
    const val HEART_RATE_DAYS = 1

    val CURRENT_DAY_ONLY_TYPES = setOf(
        "heart-rate",
    )

    val HIGH_VOLUME_CARDIAC_TYPES = setOf(
        "heart-rate",
        "heart-rate-variability",
        "electrocardiogram",
    )

    fun isCurrentDayOnly(dataType: String): Boolean = dataType in CURRENT_DAY_ONLY_TYPES

    fun daysFor(dataType: String): Int = when {
        isCurrentDayOnly(dataType) -> HEART_RATE_DAYS
        dataType in HIGH_VOLUME_CARDIAC_TYPES -> HIGH_VOLUME_CARDIAC_DAYS
        else -> GENERAL_DAYS
    }

    fun requestedDays(dataType: String, requestedDays: Int): Int =
        requestedDays.coerceIn(1, GENERAL_DAYS).coerceAtMost(daysFor(dataType))

    fun cutoffDate(dataType: String, today: LocalDate = LocalDate.now()): LocalDate =
        today.minusDays((daysFor(dataType) - 1).toLong())
}
