package com.cowork.chat.domain

import com.cowork.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_messages")
class Message(
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "sender_id")
    val senderId: Long?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: MessageType,

    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(name = "webhook_id")
    val webhookId: Long? = null,

    @Column(name = "sender_name", length = 100)
    val senderName: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    @Column(nullable = false)
    var edited: Boolean = false
        protected set

    @Column(nullable = false)
    var deleted: Boolean = false
        protected set

    fun isSentBy(userId: Long): Boolean = senderId == userId

    fun edit(content: String) {
        this.content = content
        this.edited = true
    }

    fun softDelete() {
        this.content = ""
        this.deleted = true
    }
}
