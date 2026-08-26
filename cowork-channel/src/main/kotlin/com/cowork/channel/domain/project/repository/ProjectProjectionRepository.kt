package com.cowork.channel.domain.project.repository

import com.cowork.channel.domain.project.entity.ProjectProjection
import org.springframework.data.jpa.repository.JpaRepository

interface ProjectProjectionRepository : JpaRepository<ProjectProjection, Long>
