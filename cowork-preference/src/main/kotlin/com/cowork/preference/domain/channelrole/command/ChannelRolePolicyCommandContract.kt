package com.cowork.preference.domain.channelrole.command

object ChannelRolePolicyCommandContract {
    fun policyKey(teamId: Long, channelId: Long, roleId: Long): String = "policy:$teamId:$channelId:$roleId"

    const val SCHEMA_VERSION = 2
}
