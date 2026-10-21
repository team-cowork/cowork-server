package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.GithubPullRequestProjection
import org.springframework.data.jpa.repository.JpaRepository

interface GithubPullRequestProjectionRepository : JpaRepository<GithubPullRequestProjection, String> {
    fun findByOwnerAndRepo(owner: String, repo: String): List<GithubPullRequestProjection>
    fun findByOwnerAndRepoAndNumber(owner: String, repo: String, number: Int): GithubPullRequestProjection?
}
