package com.cowork.channel.domain.project.repository

import com.cowork.channel.domain.project.entity.ProjectAccessProjection
import org.springframework.data.jpa.repository.JpaRepository

interface ProjectAccessProjectionRepository : JpaRepository<ProjectAccessProjection, String>
