package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.GithubLabelProjection
import org.springframework.data.jpa.repository.JpaRepository

interface GithubLabelProjectionRepository : JpaRepository<GithubLabelProjection, String> {
    fun findByOwnerAndRepoAndDeletedFalse(owner: String, repo: String): List<GithubLabelProjection>
}
