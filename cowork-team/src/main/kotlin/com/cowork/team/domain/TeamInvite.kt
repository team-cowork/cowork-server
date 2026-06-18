package com.cowork.team.domain

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "tb_team_invites")
class TeamInvite(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    val team: Team,

    @Column(name = "invite_code", nullable = false, length = 8, unique = true)
    val inviteCode: String,

    @Column(name = "created_by", nullable = false, updatable = false)
    val createdBy: Long,

    @Column(name = "duration", nullable = false, length = 10)
    val duration: String,

    @Column(name = "expires_at")
    val expiresAt: LocalDateTime? = null,
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null

    fun softDelete() {
        deletedAt = LocalDateTime.now()
    }

    fun isExpired(): Boolean = expiresAt?.isBefore(LocalDateTime.now()) ?: false

    fun isDeleted(): Boolean = deletedAt != null
}
