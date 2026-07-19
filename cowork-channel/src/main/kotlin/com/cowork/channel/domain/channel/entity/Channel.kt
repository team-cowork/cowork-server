package com.cowork.channel.domain.channel.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_channels")
class Channel(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "team_id", nullable = true)
    val teamId: Long?,

    @Column(name = "project_id", nullable = true)
    var projectId: Long? = null,

    @Column(nullable = false, length = 100)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: ChannelType,

    @Enumerated(EnumType.STRING)
    @Column(name = "view_type", nullable = false, length = 30)
    val viewType: ChannelViewType,

    @Column(length = 500)
    var description: String?,

    @Column(name = "is_private", nullable = false)
    var isPrivate: Boolean = false,

    @Column(nullable = false)
    var position: Int = 0,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Column(name = "dm_key", nullable = true, length = 50, unique = true)
    val dmKey: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun update(name: String?, description: String?, isPrivate: Boolean?) {
        name?.let { this.name = it }
        description?.let { this.description = it }
        isPrivate?.let { this.isPrivate = it }
    }

    fun updatePosition(position: Int) {
        this.position = position
    }

    fun assignProject(projectId: Long?) {
        this.projectId = projectId
    }
}
