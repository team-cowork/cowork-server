package com.cowork.project.client

import feign.FeignException
import org.slf4j.LoggerFactory
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class TeamClientFallbackFactory : FallbackFactory<TeamClient> {

    private val log = LoggerFactory.getLogger(TeamClientFallbackFactory::class.java)

    override fun create(cause: Throwable): TeamClient = object : TeamClient {
        override fun getMembership(teamId: Long, userId: Long): TeamMembershipResponse {
            if (cause is FeignException && cause.status() == HttpStatus.NOT_FOUND.value()) {
                throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.NOT_FOUND)
            }
            log.error("cowork-team 호출 실패 [teamId={}, userId={}]", teamId, userId, cause)
            throw ExpectedException("팀 서비스가 응답하지 않습니다.", HttpStatus.SERVICE_UNAVAILABLE)
        }
    }
}
