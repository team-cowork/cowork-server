package com.cowork.project.github

import tools.jackson.databind.ObjectMapper
import feign.Response
import feign.codec.ErrorDecoder
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GithubAppErrorDecoder(private val objectMapper: ObjectMapper) : ErrorDecoder {
    private val logger = LoggerFactory.getLogger(GithubAppErrorDecoder::class.java)

    override fun decode(methodKey: String, response: Response): Exception {
        val status = response.status()
        if (status == 401) {
            logger.error("Failed to authenticate with cowork-github-app due to invalid internal API key")
            return ExpectedException("GitHub 연동 서버 설정에 문제가 있습니다.", HttpStatus.BAD_GATEWAY)
        }
        if (status in 400..499) {
            return ExpectedException(extractMessage(response), HttpStatus.valueOf(status))
        }
        logger.error("Failed to call cowork-github-app with {} server error", status)
        return ExpectedException("GitHub 연동 서버와 통신할 수 없습니다.", HttpStatus.BAD_GATEWAY)
    }

    private fun extractMessage(response: Response): String =
        runCatching {
            response.body()?.asInputStream()?.use { stream ->
                objectMapper.readTree(stream).get("message")?.asText()
            }
        }
            .onFailure { logger.warn("Failed to parse cowork-github-app error response body", it) }
            .getOrNull()
            ?: "GitHub 연동 서버 오류"
}
