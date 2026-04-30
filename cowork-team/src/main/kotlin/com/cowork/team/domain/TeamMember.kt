package com.cowork.team.domain

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(
    name = "tb_team_members",
    uniqueConstraints = [UniqueConstraint(name = "uq_tb_team_members", columnNames = ["team_id", "user_id"])]
)
class TeamMember(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    val team: Team,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: TeamRole = TeamRole.MEMBER,
) {
    @CreatedDate
    @Column(name = "joined_at", nullable = false, updatable = false)
    var joinedAt: LocalDateTime? = null

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    var createdBy: Long? = null

    fun changeRole(newRole: TeamRole) {
        role = newRole
    }
}