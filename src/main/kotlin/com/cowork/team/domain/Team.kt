package com.cowork.team.domain

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
@Table(name = "tb_teams")
class Team(
    @Column(nullable = false, length = 100)
    var name: String,

    @Column(length = 500)
    var description: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var visibility: TeamVisibility,

    @Column(name = "owner_id", nullable = false)
    var ownerId: Long,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    @Column(name = "icon_key", length = 500)
    var iconKey: String? = null
        protected set

    fun update(name: String?, description: String?, visibility: TeamVisibility?) {
        name?.let { this.name = it }
        description?.let { this.description = it.ifBlank { null } }
        visibility?.let { this.visibility = it }
    }

    fun changeIcon(iconKey: String?) {
        this.iconKey = iconKey
    }

    fun transferOwnership(newOwnerId: Long) {
        this.ownerId = newOwnerId
    }
}
