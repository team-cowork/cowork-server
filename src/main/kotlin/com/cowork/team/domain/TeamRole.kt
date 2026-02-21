package com.cowork.team.domain

import com.cowork.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_team_roles")
class TeamRole(
    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(nullable = false, length = 50)
    var name: String,

    @Column(name = "color_hex", nullable = false, length = 7)
    var colorHex: String = DEFAULT_COLOR,

    @Column(nullable = false)
    var priority: Int = 0,

    @Column(nullable = false)
    var mentionable: Boolean = false,

    @Convert(converter = TeamPermissionsConverter::class)
    @Column(nullable = false, length = 500)
    var permissions: Set<TeamPermission> = emptySet(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    fun update(
        name: String?,
        colorHex: String?,
        priority: Int?,
        mentionable: Boolean?,
        permissions: Set<TeamPermission>?,
    ) {
        name?.let { this.name = it }
        colorHex?.let { this.colorHex = it }
        priority?.let { this.priority = it }
        mentionable?.let { this.mentionable = it }
        permissions?.let { this.permissions = it }
    }

    companion object {
        const val DEFAULT_COLOR = "#99AAB5"
    }
}
