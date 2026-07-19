package com.cowork.project.domain.github.service

import feign.FeignException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

/**
 * `github-app` 호출 중 발생하는 네트워크/타임아웃성 `FeignException`을
 * `ExpectedException(BAD_GATEWAY)`로 변환한다.
 */
@Component
class GithubAppCallExecutor {
    private val logger = LoggerFactory.getLogger(GithubAppCallExecutor::class.java)

    fun <T> execute(call: () -> T): T = try {
        call()
    } catch (e: FeignException) {
        logger.error("Failed to call cowork-github-app due to network or timeout error", e)
        throw ExpectedException("GitHub 연동 서버와 통신할 수 없습니다.", HttpStatus.BAD_GATEWAY)
    }
}
