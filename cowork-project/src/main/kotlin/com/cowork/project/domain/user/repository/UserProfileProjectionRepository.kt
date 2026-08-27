package com.cowork.project.domain.user.repository

import com.cowork.project.domain.user.entity.UserProfileProjection
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserProfileProjectionRepository : JpaRepository<UserProfileProjection, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT profile FROM UserProfileProjection profile WHERE profile.userId = :userId")
    fun findByIdForUpdate(@Param("userId") userId: Long): UserProfileProjection?

    fun findAllByGithubIdAndDeletedFalse(githubId: String): List<UserProfileProjection>
}
