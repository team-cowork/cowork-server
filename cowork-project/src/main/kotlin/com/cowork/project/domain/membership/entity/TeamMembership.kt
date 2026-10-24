package com.cowork.project.domain.membership.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "tb_team_memberships")
class TeamMembership(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false, length = 20)
    var role: String,

    @Column(nullable = false)
    var active: Boolean = true,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant = Instant.EPOCH,

    @Column(name = "authorization_version")
    var authorizationVersion: Instant? = null,
) {
    fun applyUpsert(newRole: String, occurredAt: Instant, newAuthorizationVersion: Instant? = null) {
        authorizationVersion = if (occurredAt == sourceOccurredAt) {
            listOfNotNull(authorizationVersion, newAuthorizationVersion).maxOrNull()
        } else newAuthorizationVersion
        role = newRole
        active = true
        sourceOccurredAt = occurredAt
    }

    fun markDeleted(occurredAt: Instant, newAuthorizationVersion: Instant? = null) {
        authorizationVersion = if (occurredAt == sourceOccurredAt) {
            listOfNotNull(authorizationVersion, newAuthorizationVersion).maxOrNull()
        } else newAuthorizationVersion
        active = false
        sourceOccurredAt = occurredAt
    }
}
