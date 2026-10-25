package com.cowork.channel.domain.webhook.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.LocalDateTime

@Entity
@Immutable
@Table(name = "tb_webhooks")
class Webhook(

    @Id
    val id: Long = 0,

    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(name = "is_secure", nullable = false)
    val isSecure: Boolean = false,

    @Column(length = 255)
    val token: String? = null,

    @Column(name = "avatar_url", length = 512)
    val avatarUrl: String?,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
