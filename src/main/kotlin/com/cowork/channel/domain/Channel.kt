package com.cowork.channel.domain

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
@Table(name = "tb_channels")
class Channel(
    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "project_id")
    var projectId: Long?,

    @Column(nullable = false, length = 100)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "view_type", nullable = false, length = 30)
    val viewType: ChannelViewType,

    @Column(length = 500)
    var description: String?,

    @Column(name = "is_private", nullable = false)
    var isPrivate: Boolean,

    @Column(nullable = false)
    var position: Int,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: ChannelType = viewType.channelType

    fun update(name: String?, description: String?, isPrivate: Boolean?) {
        name?.let { this.name = it }
        description?.let { this.description = it }
        isPrivate?.let { this.isPrivate = it }
    }

    fun assignProject(projectId: Long?) {
        this.projectId = projectId
    }

    fun moveTo(position: Int) {
        this.position = position
    }
}
