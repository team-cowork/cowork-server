package com.cowork.preference.domain.channelrole.command.repository

data class ProcessedChannelRolePolicyCommandMatches(
    val byOperationId: ProcessedChannelRolePolicyCommand?,
    val byActorAndIdempotencyKey: ProcessedChannelRolePolicyCommand?,
)
