package com.cowork.channel.domain

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "tb_webhooks")
class Webhook(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(name = "is_secure", nullable = false)
    var isSecure: Boolean = false,

    @Column(length = 255)
    var token: String? = null,

    @Column(name = "avatar_url", length = 512)
    var avatarUrl: String?,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun update(name: String?, avatarUrl: String?, isSecure: Boolean?) {
        name?.let { this.name = it }
        avatarUrl?.let { this.avatarUrl = it }
        isSecure?.let { newSecure ->
            this.isSecure = newSecure
            this.token = if (newSecure) UUID.randomUUID().toString() else null
        }
    }
}
