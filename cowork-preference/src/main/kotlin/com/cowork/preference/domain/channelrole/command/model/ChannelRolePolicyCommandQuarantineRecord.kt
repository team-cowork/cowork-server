package com.cowork.preference.domain.channelrole.command.model

data class ChannelRolePolicyCommandQuarantineRecord(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val key: String?,
    val payload: String?,
    val reason: String,
)
