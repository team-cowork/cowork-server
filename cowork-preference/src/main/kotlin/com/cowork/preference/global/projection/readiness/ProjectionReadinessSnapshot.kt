package com.cowork.preference.global.projection.readiness

data class ProjectionReadinessSnapshot(val ready: Boolean, val reason: String, val initialized: Boolean)
