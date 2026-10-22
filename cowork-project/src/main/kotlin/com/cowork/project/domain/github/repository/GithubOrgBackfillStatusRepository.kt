package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.GithubOrgBackfillStatus
import org.springframework.data.jpa.repository.JpaRepository

interface GithubOrgBackfillStatusRepository : JpaRepository<GithubOrgBackfillStatus, String>
