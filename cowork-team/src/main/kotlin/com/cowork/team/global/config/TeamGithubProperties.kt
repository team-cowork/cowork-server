package com.cowork.team.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration
class TeamGithubPropertiesConfig {
    @Bean
    fun teamGithubProperties(environment: Environment): TeamGithubProperties = TeamGithubProperties(
        stateSecret = environment.getRequiredProperty("team-github.state-secret"),
        appSlug = environment.getRequiredProperty("team-github.app-slug"),
    )
}

data class TeamGithubProperties(val stateSecret: String, val appSlug: String)
