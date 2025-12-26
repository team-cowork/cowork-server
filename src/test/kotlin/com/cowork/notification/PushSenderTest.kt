package com.cowork.notification

import com.cowork.notification.domain.DevicePlatform
import com.cowork.notification.domain.DeviceToken
import com.cowork.notification.domain.NotificationMessage
import com.cowork.notification.domain.NotificationType
import com.cowork.notification.push.NoOpPushSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PushSenderTest {

    @Test
    fun `FCM을 설정하지 않으면 무효 토큰 없이 발송을 건너뛴다`() {
        val invalid = NoOpPushSender().send(listOf("a", "b"), NotificationMessage(NotificationType.CHAT_MESSAGE, "t", "b"))

        assertTrue(invalid.isEmpty())
    }

    @Test
    fun `기기 토큰을 다시 등록하면 사용자와 플랫폼을 바꾼다`() {
        val token = DeviceToken(userId = 1, token = "fcm", platform = DevicePlatform.ANDROID)

        token.reassign(2, DevicePlatform.WEB)

        assertEquals(2, token.userId)
        assertEquals(DevicePlatform.WEB, token.platform)
    }
}
