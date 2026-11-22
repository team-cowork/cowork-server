package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubRepoProjection
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.presentation.data.response.GithubRepoSummaryResDto
import com.cowork.project.domain.github.repository.GithubRepoProjectionRepository
import org.springframework.stereotype.Component

/** org의 repo 목록을 조회한다. 그 org의 repo 목록 동기화 마커가 있어야 projection을 믿는다. */
@Component
class GithubRepoReader(
    private val githubSyncMarkers: GithubSyncMarkers,
    private val githubRepoProjectionRepository: GithubRepoProjectionRepository,
) {
    fun listOrgRepos(orgLogin: String): List<GithubRepoSummaryResDto> {
        githubSyncMarkers.requireSynced(GithubSyncStream.REPO, orgLogin)
        return githubRepoProjectionRepository.findByOwnerAndDeletedFalse(orgLogin).map { it.toResDto() }
    }

    // owner/repo는 식별용으로 소문자로 정규화돼 있으므로, 화면에 보일 이름은 원래 대소문자의 fullName에서 꺼낸다.
    private fun GithubRepoProjection.toResDto() = GithubRepoSummaryResDto(
        name = fullName.substringAfter('/'),
        fullName = fullName,
        private = private,
        htmlUrl = htmlUrl,
    )
}
