package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubRepoProjection
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.presentation.data.response.GithubRepoSummaryResDto
import com.cowork.project.domain.github.repository.GithubRepoProjectionRepository
import org.springframework.stereotype.Component

/** org의 repo 목록을 조회한다. 그 org의 repo 목록 동기화 마커가 있어야 projection을 믿는다. */
@Component
class GithubRepoReader(
    private val syncMarkers: GithubSyncMarkers,
    private val repoProjectionRepository: GithubRepoProjectionRepository,
) {
    fun listOrgRepos(orgLogin: String): List<GithubRepoSummaryResDto> {
        syncMarkers.requireSynced(GithubSyncStream.REPO, orgLogin)
        return repoProjectionRepository.findByOwnerAndDeletedFalse(orgLogin).map { it.toResDto() }
    }

    private fun GithubRepoProjection.toResDto() = GithubRepoSummaryResDto(
        name = repo,
        fullName = fullName,
        private = private,
        htmlUrl = htmlUrl,
    )
}
