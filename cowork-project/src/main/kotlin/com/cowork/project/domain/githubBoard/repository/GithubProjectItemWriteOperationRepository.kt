package com.cowork.project.domain.githubBoard.repository

import com.cowork.project.domain.githubBoard.entity.GithubProjectItemWriteOperation
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GithubProjectItemWriteOperationRepository : JpaRepository<GithubProjectItemWriteOperation, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT operation FROM GithubProjectItemWriteOperation operation WHERE operation.operationId = :operationId")
    fun findByIdForUpdate(@Param("operationId") operationId: String): GithubProjectItemWriteOperation?

    fun findByOperationIdAndBoardId(operationId: String, boardId: Long): GithubProjectItemWriteOperation?
}
