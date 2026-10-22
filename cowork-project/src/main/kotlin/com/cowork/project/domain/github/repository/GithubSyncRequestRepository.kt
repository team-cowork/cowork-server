package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.GithubSyncRequest
import org.springframework.data.jpa.repository.JpaRepository

interface GithubSyncRequestRepository : JpaRepository<GithubSyncRequest, String>
