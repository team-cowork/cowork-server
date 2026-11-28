package com.cowork.team.global.consumer

import org.springframework.modulith.NamedInterface

@NamedInterface("consumer", propagate = false)
object Topics {
    const val TEAM_GITHUB_CONNECTED = "team.github.connected"
    const val TEAM_GITHUB_DISCONNECTED = "team.github.disconnected"
    const val TEAM_GITHUB_RENAMED = "team.github.renamed"
    const val PREFERENCE_TEAM_SETTING_STATE = "preference.team-setting.state.v1"
}
