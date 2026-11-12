package com.cowork.preference.domain.access.projection.channel.model

sealed interface ChannelLifecycleRecordDecision {
    data class Apply(val event: ChannelLifecycleEvent) : ChannelLifecycleRecordDecision

    data class Quarantine(val reason: String) : ChannelLifecycleRecordDecision
}
