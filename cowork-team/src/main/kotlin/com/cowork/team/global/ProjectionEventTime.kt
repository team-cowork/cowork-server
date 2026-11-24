package com.cowork.team.global

import java.time.Instant
import java.time.temporal.ChronoUnit

internal fun Instant.toProjectionPrecision(): Instant = truncatedTo(ChronoUnit.MICROS)

internal fun nextTeamStateOccurredAt(current: Instant?, requestedAt: Instant): Instant {
    val requestedVersion = requestedAt.toProjectionPrecision()
    val minimumNextVersion = current?.toProjectionPrecision()?.plus(1, ChronoUnit.MICROS)
    return if (minimumNextVersion == null || requestedVersion.isAfter(minimumNextVersion)) {
        requestedVersion
    } else {
        minimumNextVersion
    }
}
