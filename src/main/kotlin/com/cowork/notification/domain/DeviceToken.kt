package com.cowork.notification.domain

import com.cowork.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "tb_device_tokens")
class DeviceToken(
    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(nullable = false, unique = true, length = 512)
    val token: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var platform: DevicePlatform,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    @Column(name = "last_registered_at", nullable = false)
    var lastRegisteredAt: LocalDateTime = LocalDateTime.now()
        protected set

    fun reassign(userId: Long, platform: DevicePlatform) {
        this.userId = userId
        this.platform = platform
        this.lastRegisteredAt = LocalDateTime.now()
    }
}
