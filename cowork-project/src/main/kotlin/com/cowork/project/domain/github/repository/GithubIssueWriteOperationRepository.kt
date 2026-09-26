package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.GithubIssueWriteOperation
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GithubIssueWriteOperationRepository : JpaRepository<GithubIssueWriteOperation, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT operation FROM GithubIssueWriteOperation operation WHERE operation.operationId = :operationId")
    fun findByIdForUpdate(@Param("operationId") operationId: String): GithubIssueWriteOperation?
}
