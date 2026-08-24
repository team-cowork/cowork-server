package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.presentation.data.response.GithubLabelPolicyResDto
import com.cowork.project.domain.github.service.GithubLabelPolicyReader
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.QueryGithubLabelPolicyService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryGithubLabelPolicyServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val labelPolicyReader: GithubLabelPolicyReader,
) : QueryGithubLabelPolicyService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, repoId: Long): GithubLabelPolicyResDto {
        repoAccessResolver.resolveForRead(userId, projectId, repoId)
        return GithubLabelPolicyResDto(autoApply = labelPolicyReader.readAutoApply(repoId))
    }
}
