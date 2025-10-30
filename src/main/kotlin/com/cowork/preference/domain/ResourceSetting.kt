package com.cowork.preference.domain

import com.cowork.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(
    name = "tb_resource_settings",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_tb_resource_settings_type_id", columnNames = ["resource_type", "resource_id"]),
    ],
)
class ResourceSetting(
    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 30)
    val resourceType: ResourceType,

    @Column(name = "resource_id", nullable = false)
    val resourceId: Long,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSON")
    var settings: MutableMap<String, Any?> = mutableMapOf(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    fun merge(changes: Map<String, Any?>) {
        val merged = settings.toMutableMap()
        changes.forEach { (key, value) ->
            if (value == null) merged.remove(key) else merged[key] = value
        }
        settings = merged
    }
}
