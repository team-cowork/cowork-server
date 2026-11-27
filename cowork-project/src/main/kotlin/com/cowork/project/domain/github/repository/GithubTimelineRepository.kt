package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.presentation.data.response.GithubTimelineActivityResDto
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class GithubTimelineParent { ISSUE, PULL_REQUEST, MISSING }

data class GithubTimelineSnapshot(
    val id: UUID,
    val parent: GithubTimelineParent,
    val count: Int,
    val completedAt: Instant,
)

@Repository
class GithubTimelineRepository(private val jdbcTemplate: JdbcTemplate, private val objectMapper: ObjectMapper) {
    fun discardUnpublished(subject: String, snapshotId: UUID) {
        // 반복해서 실패한 동기화가 남긴 행을 지우되, 조회 중인 스냅샷은 완료 레코드가 올 때까지 유지한다.
        jdbcTemplate.update(
            """
            DELETE FROM tb_github_timeline_items WHERE subject = ? AND snapshot_id <> ?
              AND snapshot_id NOT IN (SELECT snapshot_id FROM tb_github_timeline_snapshots WHERE subject = ?)
            """.trimIndent(),
            subject,
            snapshotId.toString(),
            subject,
        )
    }

    fun stage(subject: String, snapshotId: UUID, item: GithubTimelineActivityResDto) {
        jdbcTemplate.update(
            """
            INSERT INTO tb_github_timeline_items (subject, snapshot_id, activity_id, occurred_at_ms, payload)
            VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE payload = VALUES(payload), occurred_at_ms = VALUES(occurred_at_ms)
            """.trimIndent(),
            subject,
            snapshotId.toString(),
            item.id,
            item.occurredAt.toEpochMilli(),
            objectMapper.writeValueAsString(item),
        )
    }

    fun complete(subject: String, snapshotId: UUID, parent: GithubTimelineParent, count: Int, completedAt: Instant) {
        val staged = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tb_github_timeline_items WHERE subject = ? AND snapshot_id = ?",
            Long::class.java,
            subject,
            snapshotId.toString(),
        )
        if (staged != count.toLong()) {
            // 압축된 토픽을 재생할 때 다음 동기화가 이전 스냅샷의 일부 키를 이미 덮었을 수 있다.
            invalidate(subject)
            return
        }
        jdbcTemplate.update(
            """
            INSERT INTO tb_github_timeline_snapshots (subject, snapshot_id, parent_type, item_count, completed_at)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE snapshot_id = VALUES(snapshot_id), parent_type = VALUES(parent_type), item_count = VALUES(item_count),
                completed_at = VALUES(completed_at)
            """.trimIndent(),
            subject,
            snapshotId.toString(),
            parent.name,
            count,
            LocalDateTime.ofInstant(completedAt, ZoneOffset.UTC),
        )
        jdbcTemplate.update(
            "DELETE FROM tb_github_timeline_items WHERE subject = ? AND snapshot_id <> ?",
            subject,
            snapshotId.toString(),
        )
    }

    fun invalidate(subject: String) {
        jdbcTemplate.update("DELETE FROM tb_github_timeline_snapshots WHERE subject = ?", subject)
    }

    fun snapshot(subject: String): GithubTimelineSnapshot? = jdbcTemplate.query(
        "SELECT snapshot_id, parent_type, item_count, completed_at FROM tb_github_timeline_snapshots WHERE subject = ?",
        { row, _ ->
            GithubTimelineSnapshot(
                UUID.fromString(row.getString(1)),
                GithubTimelineParent.valueOf(row.getString(2)),
                row.getInt(3),
                row.getObject(4, LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
            )
        },
        subject,
    ).singleOrNull()

    fun list(subject: String, snapshotId: UUID, offset: Long, size: Int): List<GithubTimelineActivityResDto> =
        jdbcTemplate.query(
            """
            SELECT payload FROM tb_github_timeline_items WHERE subject = ? AND snapshot_id = ?
            ORDER BY occurred_at_ms, activity_id LIMIT ? OFFSET ?
            """.trimIndent(),
            { row, _ -> objectMapper.readValue<GithubTimelineActivityResDto>(row.getString(1)) },
            subject,
            snapshotId.toString(),
            size,
            offset,
        )
}
