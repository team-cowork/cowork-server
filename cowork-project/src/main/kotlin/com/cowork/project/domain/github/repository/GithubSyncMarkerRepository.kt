package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.GithubSyncMarker
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GithubSyncMarkerRepository : JpaRepository<GithubSyncMarker, String> {
    @Modifying
    @Query("DELETE FROM GithubSyncMarker m WHERE m.id LIKE CONCAT(:prefix, '%')")
    fun deleteByIdStartingWith(@Param("prefix") prefix: String)
}
