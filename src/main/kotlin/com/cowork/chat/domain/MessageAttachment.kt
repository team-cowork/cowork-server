package com.cowork.chat.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_message_attachments")
class MessageAttachment(
    @Column(name = "message_id", nullable = false)
    val messageId: Long,

    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "object_key", nullable = false, unique = true, length = 500)
    val objectKey: String,

    @Column(name = "file_name", nullable = false)
    val fileName: String,

    @Column(name = "content_type", nullable = false, length = 100)
    val contentType: String,

    @Column(name = "file_size", nullable = false)
    val fileSize: Long,

    @Column(name = "uploaded_by", nullable = false)
    val uploadedBy: Long,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) {
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        protected set
}
