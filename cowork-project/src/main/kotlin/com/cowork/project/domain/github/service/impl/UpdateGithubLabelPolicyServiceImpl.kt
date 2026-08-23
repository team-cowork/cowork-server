package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.presentation.data.request.UpdateGithubLabelPolicyReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubLabelPolicyResDto
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.UpdateGithubLabelPolicyService
import com.cowork.project.global.client.PreferenceClient
import feign.FeignException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class UpdateGithubLabelPolicyServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val preferenceClient: PreferenceClient,
) : UpdateGithubLabelPolicyService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, repoId: Long, request: UpdateGithubLabelPolicyReqDto): GithubLabelPolicyResDto {
        repoAccessResolver.resolveForModify(userId, projectId, repoId)
        val updated = try {
            preferenceClient.updateGithubRepoSettings(repoId, mapOf("label_auto_apply" to request.autoApply))
        } catch (e: FeignException) {
            throw ExpectedException("설정 서비스와 통신 중 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY)
        }
        return GithubLabelPolicyResDto(autoApply = updated["label_auto_apply"] as? Boolean ?: request.autoApply)
    }
}
