package com.cowork.channel.domain.project.repository

import com.cowork.channel.domain.project.entity.ProjectProjection
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProjectProjectionRepository : JpaRepository<ProjectProjection, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT projection FROM ProjectProjection projection WHERE projection.projectId = :projectId")
    fun findByIdForUpdate(@Param("projectId") projectId: Long): ProjectProjection?
}
