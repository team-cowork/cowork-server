package com.cowork.project.domain.project.repository

import com.cowork.project.domain.project.entity.ProjectAccessProjection
import org.springframework.data.jpa.repository.JpaRepository

interface ProjectAccessProjectionRepository : JpaRepository<ProjectAccessProjection, String>
