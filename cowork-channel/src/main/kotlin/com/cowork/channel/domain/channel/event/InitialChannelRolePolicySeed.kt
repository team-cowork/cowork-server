package com.cowork.channel.domain.channel.event

import java.time.Instant

data class InitialChannelRolePolicySeed(
    val actorId: Long,
    val actorMembershipVersion: Instant,
    val overwrites: List<InitialChannelRoleOverwrite>,
    val actorAuthorizationVersion: Instant,
)

data class InitialChannelRoleOverwrite(val roleId: Long, val permissions: Map<String, Boolean>)
