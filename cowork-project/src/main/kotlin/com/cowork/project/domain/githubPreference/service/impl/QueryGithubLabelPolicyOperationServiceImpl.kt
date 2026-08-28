package com.cowork.project.domain.githubPreference.service.impl

import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.githubPreference.presentation.data.response.GithubLabelPolicyOperationResDto
import com.cowork.project.domain.githubPreference.repository.GithubRepoSettingOperationRepository
import com.cowork.project.domain.githubPreference.service.QueryGithubLabelPolicyOperationService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class QueryGithubLabelPolicyOperationServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val operationRepository: GithubRepoSettingOperationRepository,
) : QueryGithubLabelPolicyOperationService {
    @Transactional(readOnly = true)
    override fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        operationId: String,
    ): GithubLabelPolicyOperationResDto {
        repoAccessResolver.resolveForRead(userId, projectId, repoId)
        val operation = operationRepository.findByOperationIdAndProjectIdAndRepoId(operationId, projectId, repoId)
            ?: throw ExpectedException("설정 변경 작업을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        return GithubLabelPolicyOperationResDto.of(operation)
    }
}
