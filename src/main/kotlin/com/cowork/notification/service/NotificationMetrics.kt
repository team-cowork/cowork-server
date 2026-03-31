package com.cowork.notification.service

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 현재 SSE 알림 스트림에 연결된 수를 게이지로 노출한다.
 */
@Component
class NotificationMetrics(
    sseEmitterRegistry: SseEmitterRegistry,
    meterRegistry: MeterRegistry,
) {
    init {
        Gauge.builder("cowork.notification.sse.connections") { sseEmitterRegistry.connectionCount().toDouble() }
            .description("현재 SSE 알림 연결 수")
            .register(meterRegistry)
    }
}
