package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.presentation.data.request.UpdateGithubLabelPolicyReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubLabelPolicyResDto
import com.cowork.project.domain.github.service.GithubLabelPolicyReader
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.UpdateGithubLabelPolicyService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UpdateGithubLabelPolicyServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val labelPolicyReader: GithubLabelPolicyReader,
) : UpdateGithubLabelPolicyService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, repoId: Long, request: UpdateGithubLabelPolicyReqDto): GithubLabelPolicyResDto {
        repoAccessResolver.resolveForModify(userId, projectId, repoId)
        return GithubLabelPolicyResDto(autoApply = labelPolicyReader.writeAutoApply(repoId, request.autoApply))
    }
}
