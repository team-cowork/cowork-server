package com.cowork.project.domain.project.event

import com.cowork.project.global.projection.toProjectionPrecision
import java.time.Instant
import java.time.temporal.ChronoUnit

internal fun nextMonotonicStateVersion(current: Instant, requested: Instant): Instant = maxOf(
    requested.toProjectionPrecision(),
    current.toProjectionPrecision().plus(1, ChronoUnit.MICROS),
)
