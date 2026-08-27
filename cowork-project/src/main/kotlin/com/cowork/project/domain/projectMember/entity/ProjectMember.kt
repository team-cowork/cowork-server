package com.cowork.project.domain.projectMember.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.time.LocalDateTime

@Entity
@Table(name = "tb_project_members")
class ProjectMember(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: ProjectMemberRole = ProjectMemberRole.VIEWER,

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    val joinedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant = Instant.EPOCH,
) {
    fun updateRole(newRole: ProjectMemberRole) {
        role = newRole
    }
}
