package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.presentation.data.request.UpdateGithubLabelPolicyReqDto
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.UpdateGithubLabelPolicyService
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperationStatus
import com.cowork.project.domain.githubPreference.event.GITHUB_REPO_SETTING_COMMAND_TOPIC
import com.cowork.project.domain.githubPreference.event.GithubRepoSettingValue
import com.cowork.project.domain.githubPreference.event.UpdateGithubRepoSettingCommand
import com.cowork.project.domain.githubPreference.presentation.data.response.GithubLabelPolicyOperationAcceptedResDto
import com.cowork.project.domain.githubPreference.repository.GithubRepoSettingOperationSubmissionRepository
import com.cowork.project.domain.githubPreference.repository.GithubRepoSettingOperationSubmissionRepository.InsertResult
import com.cowork.project.global.outbox.OutboxWriter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.util.UUID

@Service
class UpdateGithubLabelPolicyServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val submissionRepository: GithubRepoSettingOperationSubmissionRepository,
    private val outboxWriter: OutboxWriter,
) : UpdateGithubLabelPolicyService {

    @Transactional
    override fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        idempotencyKey: String,
        request: UpdateGithubLabelPolicyReqDto,
    ): GithubLabelPolicyOperationAcceptedResDto {
        val normalizedKey = normalizeIdempotencyKey(idempotencyKey)
        val operationId = UUID.randomUUID().toString()
        val insertion = submissionRepository.insertPendingOrGet(
            operationId = operationId,
            idempotencyKey = normalizedKey,
            projectId = projectId,
            repoId = repoId,
            requestedBy = userId,
            requestedAutoApply = request.autoApply,
        )
        when (insertion) {
            InsertResult.Inserted -> Unit

            is InsertResult.Existing -> {
                val operation = insertion.operation
                if (!operation.matches(projectId, repoId, userId, request.autoApply)) {
                    throw ExpectedException(
                        "같은 Idempotency-Key를 다른 설정 변경 요청에 사용할 수 없습니다.",
                        HttpStatus.CONFLICT,
                    )
                }
                return GithubLabelPolicyOperationAcceptedResDto.of(operation)
            }
        }

        repoAccessResolver.resolveForModifyForUpdate(userId, projectId, repoId)
        val command = UpdateGithubRepoSettingCommand(
            operationId = operationId,
            idempotencyKey = normalizedKey,
            repoId = repoId,
            settings = GithubRepoSettingValue(request.autoApply),
            requestedBy = userId,
            occurredAt = Instant.now(),
        )
        outboxWriter.enqueue(GITHUB_REPO_SETTING_COMMAND_TOPIC, repoId.toString(), command)
        return GithubLabelPolicyOperationAcceptedResDto(operationId, GithubRepoSettingOperationStatus.PENDING)
    }

    private fun normalizeIdempotencyKey(raw: String): String {
        val normalized = raw.trim()
        if (normalized.isEmpty() || normalized.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw ExpectedException(
                "Idempotency-Key는 1자 이상 ${MAX_IDEMPOTENCY_KEY_LENGTH}자 이하여야 합니다.",
                HttpStatus.BAD_REQUEST,
            )
        }
        return normalized
    }

    private companion object {
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 128
    }
}
