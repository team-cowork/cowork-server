package com.cowork.project.global.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(TeamGithubProperties::class)
class TeamGithubPropertiesConfig

/**
 * `cowork-team`이 발급하는 GitHub 설치 연동 state의 HMAC 서명 검증에 사용하는 설정.
 *
 * 운영 시 `cowork-team`과 정확히 같은 `TEAM_GITHUB_STATE_SECRET` 값이 설정되어야
 * 서로 발급/검증한 state를 공유할 수 있다.
 */
@ConfigurationProperties(prefix = "team-github")
data class TeamGithubProperties(
    val stateSecret: String = "",
)
