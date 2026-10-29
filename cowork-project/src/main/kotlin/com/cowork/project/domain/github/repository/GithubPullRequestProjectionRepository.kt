package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.GithubPullRequestProjection
import org.springframework.data.jpa.repository.JpaRepository

interface GithubPullRequestProjectionRepository : JpaRepository<GithubPullRequestProjection, String> {
    fun findTop100ByOwnerAndRepoOrderByNumberDesc(owner: String, repo: String): List<GithubPullRequestProjection>
    fun findTop100ByOwnerAndRepoAndStateOrderByNumberDesc(
        owner: String,
        repo: String,
        state: String,
    ): List<GithubPullRequestProjection>
    fun findByOwnerAndRepoAndNumber(owner: String, repo: String, number: Int): GithubPullRequestProjection?
}
