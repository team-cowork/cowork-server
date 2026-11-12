package com.cowork.preference.domain.access.projection.project

sealed interface ProjectLifecycleRecordDecision {
    data class Apply(val event: ProjectLifecycleEvent) : ProjectLifecycleRecordDecision

    data class Quarantine(val reason: String) : ProjectLifecycleRecordDecision
}
