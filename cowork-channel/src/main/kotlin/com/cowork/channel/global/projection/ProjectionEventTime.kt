package com.cowork.channel.global.projection

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal fun Instant.toProjectionPrecision(): Instant = truncatedTo(ChronoUnit.MICROS)

internal fun LocalDateTime.toProjectionSourceInstant(zoneId: ZoneId = ZoneId.systemDefault()): Instant =
    atZone(zoneId).toInstant().toProjectionPrecision()
