package com.cowork.project.domain.githubBoard.entity

import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.Locale

/** `github.project.state.v1`의 로컬 사본. field, view, item 순서는 조회할 때만 쓰므로 JSON 그대로 둔다. */
@Entity
@Table(name = "tb_github_project_projections")
class GithubProjectProjection(
    @Id
    @Column(nullable = false, length = 120)
    val id: String,

    @Column(nullable = false, length = 100)
    val owner: String,

    @Column(name = "project_number", nullable = false)
    val projectNumber: Int,

    @Column(name = "node_id", length = 100)
    var nodeId: String? = null,

    @Column(nullable = false, length = 500)
    var title: String = "",

    @Column(nullable = false, length = 512)
    var url: String = "",

    @Column(nullable = false)
    var closed: Boolean = false,

    @Column(name = "access_error", length = 20)
    var accessError: String? = null,

    @Column(name = "fields_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    var fieldsJson: String = "[]",

    @Column(name = "views_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    var viewsJson: String = "[]",

    @Column(name = "item_ids_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    var itemIdsJson: String = "[]",

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant = Instant.EPOCH,
) {
    /**
     * 같은 버전이면 다시 반영한다. 같은 레코드를 다시 받아도 결과가 같기 때문이다. 다만 같은 버전의 삭제를 되살리지 않는다.
     * 반영했으면 true다.
     */
    fun accepts(version: Instant, incomingDeleted: Boolean): Boolean {
        val incoming = version.toProjectionPrecision()
        val current = sourceOccurredAt.toProjectionPrecision()
        return !(current.isAfter(incoming) || (current == incoming && deleted && !incomingDeleted))
    }

    companion object {
        fun id(owner: String, projectNumber: Int): String = "${owner.lowercase(Locale.ROOT)}/$projectNumber"
    }
}
