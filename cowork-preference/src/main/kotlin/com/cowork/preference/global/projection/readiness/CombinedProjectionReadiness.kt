package com.cowork.preference.global.projection.readiness

/** 여러 projection이 모두 준비되어야 여는 readiness이며, 닫혀 있으면 첫 미준비 projection의 사유를 노출한다. */
class CombinedProjectionReadiness(private val projections: List<ProjectionReadinessView>) : ProjectionReadinessView {
    init {
        require(projections.isNotEmpty()) { "combined readiness requires at least one projection" }
    }

    override val isReady: Boolean
        get() = projections.all { it.isReady }

    override fun snapshot(): ProjectionReadinessSnapshot {
        val snapshots = projections.map(ProjectionReadinessView::snapshot)
        return snapshots.firstOrNull { !it.ready } ?: snapshots.first()
    }
}
