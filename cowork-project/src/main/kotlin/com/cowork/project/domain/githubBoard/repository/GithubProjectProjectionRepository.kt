package com.cowork.project.domain.githubBoard.repository

import com.cowork.project.domain.githubBoard.entity.GithubProjectProjection
import org.springframework.data.jpa.repository.JpaRepository

interface GithubProjectProjectionRepository : JpaRepository<GithubProjectProjection, String>
