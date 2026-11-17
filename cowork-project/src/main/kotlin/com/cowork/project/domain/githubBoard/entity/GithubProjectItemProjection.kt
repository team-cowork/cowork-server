package com.cowork.project.domain.githubBoard.entity

import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** `github.project-item.state.v1`의 로컬 사본. 필터는 레코드 전체를 읽어 서버에서 적용하므로 JSON 그대로 둔다. */
@Entity
@Table(name = "tb_github_project_item_projections")
class GithubProjectItemProjection(
    @Id
    @Column(nullable = false, length = 100)
    val id: String,

    @Column(name = "project_node_id", nullable = false, length = 100)
    var projectNodeId: String,

    @Column(nullable = false, length = 100)
    var owner: String,

    @Column(name = "payload_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    var payloadJson: String,

    @Column(nullable = false)
    var archived: Boolean = false,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant = Instant.EPOCH,
) {
    /** 더 오래된 레코드와 같은 버전에서 삭제를 되살리는 레코드는 반영하지 않는다. */
    fun accepts(version: Instant, incomingDeleted: Boolean): Boolean {
        val incoming = version.toProjectionPrecision()
        val current = sourceOccurredAt.toProjectionPrecision()
        return !(current.isAfter(incoming) || (current == incoming && deleted && !incomingDeleted))
    }
}
