package com.cowork.channel.global.projection

import java.time.Instant
import java.time.temporal.ChronoUnit

internal fun Instant.toProjectionPrecision(): Instant = truncatedTo(ChronoUnit.MICROS)
