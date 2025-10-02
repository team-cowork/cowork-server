package com.cowork.channel.domain

import com.cowork.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_webhooks")
class Webhook(
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(name = "avatar_url", length = 512)
    var avatarUrl: String?,

    @Column(nullable = false, length = 64)
    var token: String,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    fun update(name: String?, avatarUrl: String?) {
        name?.let { this.name = it }
        avatarUrl?.let { this.avatarUrl = it }
    }

    fun rotateToken(token: String) {
        this.token = token
    }
}
