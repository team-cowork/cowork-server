package com.cowork.channel.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(
    name = "tb_channel_members",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_tb_channel_members_channel_user", columnNames = ["channel_id", "user_id"]),
    ],
)
class ChannelMember(
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) {
    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    var joinedAt: LocalDateTime = LocalDateTime.now()
        protected set
}
