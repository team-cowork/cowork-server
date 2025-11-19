package com.cowork.preference.domain

import com.cowork.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "tb_channel_notification_settings",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_tb_channel_notification_settings_user_channel", columnNames = ["user_id", "channel_id"]),
    ],
)
class ChannelNotificationSetting(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    fun change(enabled: Boolean) {
        this.enabled = enabled
    }
}
