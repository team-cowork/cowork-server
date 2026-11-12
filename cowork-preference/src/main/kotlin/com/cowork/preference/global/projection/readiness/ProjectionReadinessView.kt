package com.cowork.preference.global.projection.readiness

interface ProjectionReadinessView {
    val isReady: Boolean

    fun snapshot(): ProjectionReadinessSnapshot
}
