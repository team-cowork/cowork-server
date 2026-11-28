package com.cowork.team.global.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.modulith.NamedInterface

@JsonIgnoreProperties(ignoreUnknown = true)
@NamedInterface("consumer", propagate = false)
data class TeamGithubConnectedPayload(
    val state: String,
    val installationId: Long,
    val orgLogin: String,
    val revision: Long = 0,
)
