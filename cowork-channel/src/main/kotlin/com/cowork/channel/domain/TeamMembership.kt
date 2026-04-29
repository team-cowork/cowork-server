package com.cowork.channel.domain

import jakarta.persistence.*

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
)
