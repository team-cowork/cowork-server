package com.cowork.channel.domain

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_channel_members")
class ChannelMember(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    val joinedAt: LocalDateTime = LocalDateTime.now(),
)
