package com.cowork.project.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(
    name = "tb_project_members",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_tb_project_members_project_user", columnNames = ["project_id", "user_id"]),
    ],
)
class ProjectMember(
    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: ProjectMemberRole,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) {
    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    var joinedAt: LocalDateTime = LocalDateTime.now()
        protected set

    fun changeRole(role: ProjectMemberRole) {
        this.role = role
    }
}
