package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.GithubRepoProjection
import org.springframework.data.jpa.repository.JpaRepository

interface GithubRepoProjectionRepository : JpaRepository<GithubRepoProjection, String> {
    fun findByOwnerAndDeletedFalse(owner: String): List<GithubRepoProjection>
}
