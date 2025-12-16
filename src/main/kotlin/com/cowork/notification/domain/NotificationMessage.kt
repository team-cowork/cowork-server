package com.cowork.notification.domain

import java.time.LocalDateTime

data class NotificationMessage(
    val type: NotificationType,
    val title: String,
    val body: String,
    val data: Map<String, String> = emptyMap(),
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
