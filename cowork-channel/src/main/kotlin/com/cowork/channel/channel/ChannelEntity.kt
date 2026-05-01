package com.cowork.channel.channel

import com.cowork.channel.audit.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_channels")
class ChannelEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    val type: ChannelType,

    @Column(name = "name", nullable = false, length = 100)
    val name: String,

    @Column(name = "notice", length = 500)
    val notice: String? = null,
) : BaseEntity()

