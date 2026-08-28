package com.cowork.project.domain.github.event

/**
 * `cowork-notification`(Go, `internal/infra/kafka/consumer.go`)이 소비하는
 * `notification.trigger` 토픽의 실제 스키마. `cowork-team`의 `TeamEventPayload`를
 * 그대로 재사용하면 안 된다 — 필드명이 Go 구조체와 어긋나 조용히 default 케이스로
 * 빠지는 기존 불일치가 있다. `cowork-chat`의 `NotificationTriggerEvent`와 동일한
 * 형태(`type`/`targetUserIds`/`forcedUserIds`/`data`)를 따른다.
 */
data class NotificationTriggerEvent(
    val type: String,
    val targetUserIds: List<Long>,
    val forcedUserIds: List<Long> = emptyList(),
    val data: Map<String, Any?>,
)
