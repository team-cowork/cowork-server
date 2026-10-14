package com.cowork.team.domain.team.repository

import com.cowork.team.domain.team.entity.TeamIconUpload
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamIconUploadRepository : JpaRepository<TeamIconUpload, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM TeamIconUpload u WHERE u.objectKey = :objectKey")
    fun findByObjectKeyForUpdate(@Param("objectKey") objectKey: String): TeamIconUpload?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM TeamIconUpload u WHERE u.uploadKey = :uploadKey")
    fun findByUploadKeyForUpdate(@Param("uploadKey") uploadKey: String): TeamIconUpload?
}
