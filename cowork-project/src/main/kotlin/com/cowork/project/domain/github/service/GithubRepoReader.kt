package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.entity.GithubRepoProjection
import com.cowork.project.domain.github.presentation.data.response.GithubRepoSummaryResDto
import com.cowork.project.domain.github.repository.GithubOrgBackfillStatusRepository
import com.cowork.project.domain.github.repository.GithubRepoProjectionRepository
import org.springframework.stereotype.Component

/**
 * org의 repo 목록을 조회한다. 그 org의 GitHub 전체 백필이 끝났으면 로컬 projection을, 아니면
 * (빈 목록과 "아직 백필 전"을 구분할 방법이 없으므로) `GithubAppClient`로 직접 조회한다.
 */
@Component
class GithubRepoReader(
    private val backfillStatusRepository: GithubOrgBackfillStatusRepository,
    private val repoProjectionRepository: GithubRepoProjectionRepository,
    private val githubAppClient: GithubAppClient,
    private val callExecutor: GithubAppCallExecutor,
) {
    fun listOrgRepos(orgLogin: String): List<GithubRepoSummaryResDto> {
        if (!backfillStatusRepository.existsById(orgLogin)) {
            return callExecutor.execute { githubAppClient.listOrgRepos(orgLogin) }
        }

        return repoProjectionRepository.findByOwnerAndDeletedFalse(orgLogin).map { it.toResDto() }
    }

    private fun GithubRepoProjection.toResDto() = GithubRepoSummaryResDto(
        name = repo,
        fullName = fullName,
        private = private,
        htmlUrl = htmlUrl,
    )
}
