package com.cowork.team.global.consumer

data class TeamGithubConnectedPayload(val state: String, val installationId: Long, val orgLogin: String)
