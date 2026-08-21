package com.cowork.team.global.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class TeamGithubDisconnectedPayload(val installationId: Long)
