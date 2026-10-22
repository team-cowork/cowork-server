package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.GithubSyncMarker
import org.springframework.data.jpa.repository.JpaRepository

interface GithubSyncMarkerRepository : JpaRepository<GithubSyncMarker, String>
