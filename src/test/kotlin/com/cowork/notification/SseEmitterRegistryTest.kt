package com.cowork.notification

import com.cowork.notification.service.SseEmitterRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SseEmitterRegistryTest {

    private val registry = SseEmitterRegistry()

    @Test
    fun `구독하지 않은 사용자에게는 보내지 않는다`() {
        assertFalse(registry.send(1, "notification", mapOf("title" to "hello")))
        assertFalse(registry.isConnected(1))
    }

    @Test
    fun `구독한 사용자에게는 이벤트를 보낸다`() {
        registry.subscribe(1)

        assertTrue(registry.isConnected(1))
        assertTrue(registry.send(1, "notification", mapOf("title" to "hello")))
        assertFalse(registry.isConnected(2))
    }

    @Test
    fun `연결이 없을 때 하트비트를 보내도 실패하지 않는다`() {
        registry.heartbeat()

        assertFalse(registry.isConnected(1))
    }
}
