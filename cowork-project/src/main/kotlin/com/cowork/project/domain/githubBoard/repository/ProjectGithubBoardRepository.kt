package com.cowork.project.domain.githubBoard.repository

import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProjectGithubBoardRepository : JpaRepository<ProjectGithubBoard, Long> {
    fun findAllByProjectIdOrderByIdAsc(projectId: Long): List<ProjectGithubBoard>

    fun findByIdAndProjectId(id: Long, projectId: Long): ProjectGithubBoard?

    fun existsByProjectIdAndOwnerLoginAndProjectNumber(projectId: Long, ownerLogin: String, projectNumber: Int): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT board FROM ProjectGithubBoard board WHERE board.projectId IN :projectIds")
    fun findAllByProjectIdInForUpdate(@Param("projectIds") projectIds: Collection<Long>): List<ProjectGithubBoard>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT board FROM ProjectGithubBoard board WHERE board.teamId = :teamId")
    fun findAllByTeamIdForUpdate(@Param("teamId") teamId: Long): List<ProjectGithubBoard>
}
