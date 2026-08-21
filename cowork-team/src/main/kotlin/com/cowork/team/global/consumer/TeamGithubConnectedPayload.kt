package com.cowork.team.global.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class TeamGithubConnectedPayload(val state: String, val installationId: Long, val orgLogin: String)
