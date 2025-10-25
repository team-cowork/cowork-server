package com.cowork.channel.domain

import com.cowork.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_threads")
class ChannelThread(
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(name = "parent_message_id", nullable = false)
    val parentMessageId: Long,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Column(name = "is_archived", nullable = false)
    var isArchived: Boolean = false,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    fun update(name: String?, isArchived: Boolean?) {
        name?.let { this.name = it }
        isArchived?.let { this.isArchived = it }
    }
}
