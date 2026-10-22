package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.GithubIssueProjection
import org.springframework.data.jpa.repository.JpaRepository

interface GithubIssueProjectionRepository : JpaRepository<GithubIssueProjection, String> {
    fun findByOwnerAndRepoOrderByNumberDesc(owner: String, repo: String): List<GithubIssueProjection>
    fun findByOwnerAndRepoAndNumber(owner: String, repo: String, number: Int): GithubIssueProjection?
}
