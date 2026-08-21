package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.TeamGithubInstallation
import org.springframework.data.jpa.repository.JpaRepository

interface TeamGithubInstallationRepository : JpaRepository<TeamGithubInstallation, Long> {
    fun findByInstallationId(installationId: Long): TeamGithubInstallation?
}
