package com.cowork.project.domain.github.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 동기화 대상별로 `cowork-github`에 마지막으로 동기화를 요청한 시각. 같은 대상을 쿨다운 동안 다시 요청하지 않는다. */
@Entity
@Table(name = "tb_github_sync_requests")
class GithubSyncRequest(
    @Id
    @Column(nullable = false, length = 300)
    val id: String,

    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant,
)
