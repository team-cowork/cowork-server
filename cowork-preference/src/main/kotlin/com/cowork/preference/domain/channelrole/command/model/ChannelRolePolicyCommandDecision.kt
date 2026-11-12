package com.cowork.preference.domain.channelrole.command.model

sealed interface ChannelRolePolicyCommandDecision {
    data class Apply(val command: ChannelRolePolicyCommand) : ChannelRolePolicyCommandDecision
    data class Reject(val envelope: ChannelRolePolicyCommandEnvelope, val reason: String) :
        ChannelRolePolicyCommandDecision
    data class Quarantine(val reason: String) : ChannelRolePolicyCommandDecision
}
