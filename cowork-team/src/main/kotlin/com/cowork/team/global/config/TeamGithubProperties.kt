package com.cowork.team.global.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(TeamGithubProperties::class)
class TeamGithubPropertiesConfig

@ConfigurationProperties(prefix = "team-github")
data class TeamGithubProperties(val stateSecret: String = "", val appSlug: String = "")
