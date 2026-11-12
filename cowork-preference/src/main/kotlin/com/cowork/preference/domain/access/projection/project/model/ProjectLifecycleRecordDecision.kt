package com.cowork.preference.domain.access.projection.project.model

sealed interface ProjectLifecycleRecordDecision {
    data class Apply(val event: ProjectLifecycleEvent) : ProjectLifecycleRecordDecision

    data class Quarantine(val reason: String) : ProjectLifecycleRecordDecision
}
