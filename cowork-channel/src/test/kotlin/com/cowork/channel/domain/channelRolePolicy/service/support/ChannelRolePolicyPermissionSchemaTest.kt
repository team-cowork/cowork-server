package com.cowork.channel.domain.channelRolePolicy.service.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ChannelRolePolicyPermissionSchemaTest {
    private val schema = ChannelRolePolicyPermissionSchema()

    @Test
    fun `알 수 없는 키는 값 타입과 무관하게 무시함`() {
        val normalized = schema.normalize(
            mapOf(
                "message_read" to true,
                "future_permission" to mapOf("nested" to true),
                "channel_member_manage" to true,
            ),
        )

        assertEquals(mapOf("message_read" to true), normalized)
    }

    @Test
    fun `보낸 키만 유지하고 누락된 키는 채우지 않음`() {
        val normalized = schema.normalize(mapOf("message_send" to false, "file_attach" to true))

        assertEquals(mapOf("message_send" to false, "file_attach" to true), normalized)
    }

    @Test
    fun `지원하는 키가 하나도 없으면 거부함`() {
        listOf(emptyMap(), mapOf("future_permission" to null), mapOf("channel_member_manage" to true)).forEach { permissions ->
            val exception = assertThrows(ExpectedException::class.java) { schema.normalize(permissions) }

            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
        }
    }

    @Test
    fun `알려진 키는 boolean 타입만 허용함`() {
        val exception = assertThrows(ExpectedException::class.java) {
            schema.normalize(mapOf("message_read" to "false"))
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }

    @Test
    fun `알려진 키의 null은 누락이 아니라 타입 오류로 거부함`() {
        val exception = assertThrows(ExpectedException::class.java) {
            schema.normalize(mapOf("message_read" to null))
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }
}
