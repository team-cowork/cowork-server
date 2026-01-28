package com.cowork.team.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "tb_team_role_assignments",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_tb_team_role_assignments_role_user", columnNames = ["role_id", "user_id"]),
    ],
)
class TeamRoleAssignment(
    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "role_id", nullable = false)
    val roleId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
)
