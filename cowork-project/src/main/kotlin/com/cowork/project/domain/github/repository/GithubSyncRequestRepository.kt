package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.GithubSyncRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface GithubSyncRequestRepository : JpaRepository<GithubSyncRequest, String> {
    // 다른 팀이 같은 repo를 동시에 연결하면 save(merge)가 예전 스냅샷을 보고 INSERT해 PK가 겹친다.
    // 연결 트랜잭션이 이 행 때문에 실패하지 않도록 upsert로 쓴다.
    @Modifying
    @Query(
        value = "INSERT INTO tb_github_sync_requests (id, requested_at) VALUES (:id, :requestedAt) " +
            "ON DUPLICATE KEY UPDATE requested_at = VALUES(requested_at)",
        nativeQuery = true,
    )
    fun upsert(@Param("id") id: String, @Param("requestedAt") requestedAt: Instant)
}
