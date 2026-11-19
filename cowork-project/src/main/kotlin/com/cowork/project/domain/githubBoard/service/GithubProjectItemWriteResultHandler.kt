package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteResult
import com.cowork.project.domain.githubBoard.repository.GithubProjectItemWriteOperationRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.logging.logger.logger

@Component
class GithubProjectItemWriteResultHandler(private val operationRepository: GithubProjectItemWriteOperationRepository) {
    /** 결과를 작업 원장에 반영한다. 이미 끝난 작업에 다시 온 결과는 무시하고 false를 돌려준다. */
    @Transactional
    fun apply(result: GithubProjectItemWriteResult): Boolean {
        val operation = requireNotNull(operationRepository.findByIdForUpdate(result.operationId)) {
            "Unknown GitHub project item write operation: ${result.operationId}"
        }
        require(operation.idempotencyKey == result.idempotencyKey) { "idempotencyKey가 작업과 일치하지 않습니다." }
        require(operation.commandType.name == result.commandType) { "commandType이 작업과 일치하지 않습니다." }
        val applied = when (result.status) {
            "SUCCEEDED" -> operation.succeed(result.result?.get("itemId") as? String)

            "FAILED" -> {
                val error = requireNotNull(result.error) { "FAILED에는 error가 필요합니다." }
                operation.fail(error.code, error.message)
            }

            else -> throw IllegalArgumentException("지원하지 않는 result 상태입니다: ${result.status}")
        }
        if (!applied) {
            logger().info(
                "Ignore redelivered result for a finished GitHub project item write [operationId={}]",
                result.operationId,
            )
            return false
        }
        operationRepository.save(operation)
        return true
    }
}
