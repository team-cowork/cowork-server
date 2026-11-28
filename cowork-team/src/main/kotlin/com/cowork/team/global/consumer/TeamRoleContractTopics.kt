package com.cowork.team.global.consumer

import org.springframework.modulith.NamedInterface

@NamedInterface("consumer", propagate = false)
object TeamRoleContractTopics {
    const val COMMAND = "preference.team-role.command.v2"
    const val STATE = "preference.team-role.state.v2"
    const val RESULT = "preference.team-role.result.v2"
    const val SCHEMA_VERSION = 2
}
