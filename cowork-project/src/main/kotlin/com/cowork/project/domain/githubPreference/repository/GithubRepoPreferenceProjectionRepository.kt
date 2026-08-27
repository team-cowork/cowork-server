package com.cowork.project.domain.githubPreference.repository

import com.cowork.project.domain.githubPreference.entity.GithubRepoPreferenceProjection
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GithubRepoPreferenceProjectionRepository : JpaRepository<GithubRepoPreferenceProjection, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT preference FROM GithubRepoPreferenceProjection preference WHERE preference.repoId = :repoId")
    fun findByIdForUpdate(@Param("repoId") repoId: Long): GithubRepoPreferenceProjection?
}
