package com.cowork.project.domain.projectMember.event

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "tb_project_member_event_tombstones",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_tb_project_member_event_tombstones_project_user",
            columnNames = ["project_id", "user_id"],
        ),
    ],
)
class ProjectMemberEventTombstone(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant,
)
