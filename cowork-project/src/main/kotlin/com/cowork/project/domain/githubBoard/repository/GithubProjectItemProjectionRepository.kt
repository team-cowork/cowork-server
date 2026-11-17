package com.cowork.project.domain.githubBoard.repository

import com.cowork.project.domain.githubBoard.entity.GithubProjectItemProjection
import org.springframework.data.jpa.repository.JpaRepository

interface GithubProjectItemProjectionRepository : JpaRepository<GithubProjectItemProjection, String> {
    fun findAllByProjectNodeIdAndDeletedFalse(projectNodeId: String): List<GithubProjectItemProjection>
}
