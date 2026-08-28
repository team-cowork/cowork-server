package com.cowork.project.domain.githubPreference.service

import com.cowork.project.domain.githubPreference.event.GithubRepoSettingResult
import com.cowork.project.domain.githubPreference.repository.GithubRepoPreferenceProjectionRepository
import com.cowork.project.domain.githubPreference.repository.GithubRepoSettingOperationRepository
import com.cowork.project.global.projection.toProjectionPrecision
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class GithubRepoSettingResultHandler(
    private val operationRepository: GithubRepoSettingOperationRepository,
    private val preferenceRepository: GithubRepoPreferenceProjectionRepository,
) {
    @Transactional
    fun apply(result: GithubRepoSettingResult) {
        val observedState = if (result.status == "SUCCEEDED") {
            preferenceRepository.findByIdForUpdate(result.repoId)
        } else {
            null
        }
        val operation = requireNotNull(operationRepository.findByIdForUpdate(result.operationId)) {
            "알 수 없는 GitHub 저장소 설정 작업입니다: ${result.operationId}"
        }
        require(operation.idempotencyKey == result.idempotencyKey) { "idempotencyKey가 작업과 일치하지 않습니다." }
        require(operation.repoId == result.repoId) { "repoId가 작업과 일치하지 않습니다." }

        when (result.status) {
            "SUCCEEDED" -> {
                val autoApply = requireNotNull(result.settings).labelAutoApply
                val stateOccurredAt = requireNotNull(result.stateOccurredAt)
                require(operation.requestedAutoApply == autoApply) {
                    "SUCCEEDED result의 설정 값이 요청한 값과 일치하지 않습니다."
                }
                operation.markProcessing(autoApply, stateOccurredAt)
                if (observedState != null &&
                    !observedState.sourceOccurredAt.toProjectionPrecision()
                        .isBefore(stateOccurredAt.toProjectionPrecision())
                ) {
                    operation.completeIfObserved(
                        observedState.labelAutoApply,
                        observedState.deleted,
                        observedState.sourceOccurredAt,
                    )
                }
            }
            "FAILED" -> requireNotNull(result.error).let { operation.fail(it.code, it.message) }
            else -> error("지원하지 않는 GitHub 저장소 설정 결과 상태입니다: ${result.status}")
        }
        operationRepository.save(operation)
    }
}
