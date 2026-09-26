package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.event.GithubIssueWriteResult
import com.cowork.project.domain.github.repository.GithubIssueWriteOperationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class GithubIssueWriteResultHandler(
    private val operationRepository: GithubIssueWriteOperationRepository,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(GithubIssueWriteResultHandler::class.java)

    @Transactional
    fun apply(result: GithubIssueWriteResult) {
        val operation = requireNotNull(operationRepository.findByIdForUpdate(result.operationId)) {
            "알 수 없는 GitHub 이슈 쓰기 작업입니다: ${result.operationId}"
        }
        require(operation.idempotencyKey == result.idempotencyKey) { "idempotencyKey가 작업과 일치하지 않습니다." }

        val applied = when (result.status) {
            "SUCCEEDED" -> operation.succeed(result.result?.let { objectMapper.writeValueAsString(it) })
            "FAILED" -> {
                val error = requireNotNull(result.error) { "FAILED에는 error가 필요합니다." }
                operation.fail(error.code, error.message)
            }
            else -> error("지원하지 않는 GitHub 이슈 쓰기 result 상태입니다: ${result.status}")
        }
        if (!applied) {
            logger.info(
                "Ignore redelivered result for an already-terminal GitHub issue write operation [operationId={}, status={}]",
                result.operationId,
                result.status,
            )
        }
        operationRepository.save(operation)
    }
}
