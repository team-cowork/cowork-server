package com.cowork.channel.domain

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_threads")
class Thread(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(name = "parent_message_id", nullable = false, length = 24)
    val parentMessageId: String,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Column(name = "is_archived", nullable = false)
    var isArchived: Boolean = false,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun update(name: String?, isArchived: Boolean?) {
        name?.let { this.name = it }
        isArchived?.let { this.isArchived = it }
    }
}
