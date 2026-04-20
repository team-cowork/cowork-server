package com.cowork.team.domain

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_teams")
class Team(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(length = 500)
    var description: String?,

    @Column(name = "icon_url", length = 512)
    var iconUrl: String?,

    @Column(name = "owner_id", nullable = false)
    val ownerId: Long,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun update(name: String?, description: String?, iconUrl: String?) {
        name?.let { this.name = it }
        description?.let { this.description = it }
        iconUrl?.let { this.iconUrl = it }
    }
}