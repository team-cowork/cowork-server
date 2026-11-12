package com.cowork.preference.domain.teamrole.model

import java.time.Instant

data class TeamRoleMemberFence(val teamId: Long, val accountId: Long, val stateOccurredAt: Instant)
