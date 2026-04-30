package com.cowork.team.domain

import com.cowork.team.audit.BaseEntity
import jakarta.persistence.*

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
) : BaseEntity() {
    fun update(name: String?, description: String?, iconUrl: String?) {
        name?.let { this.name = it }
        description?.let { this.description = it }
        iconUrl?.let { this.iconUrl = it }
    }
}