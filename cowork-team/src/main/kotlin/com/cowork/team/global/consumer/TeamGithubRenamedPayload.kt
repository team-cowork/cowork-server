package com.cowork.team.global.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class TeamGithubRenamedPayload(val installationId: Long, val orgLogin: String, val revision: Long = 0)
