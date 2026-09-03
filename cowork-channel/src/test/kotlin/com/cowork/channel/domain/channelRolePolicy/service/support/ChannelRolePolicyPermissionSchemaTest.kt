package com.cowork.channel.domain.channelRolePolicy.service.support

import com.cowork.channel.domain.channelRolePolicy.presentation.data.request.UpsertChannelRolePolicyRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.module.kotlin.jacksonObjectMapper

class ChannelRolePolicyPermissionSchemaTest {
    private val schema = ChannelRolePolicyPermissionSchema()
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `알 수 없는 키는 값 타입과 무관하게 무시함`() {
        val normalized = schema.normalize(
            mapOf(
                "message_read" to true,
                "future_permission" to mapOf("nested" to true),
            ),
        )

        assertEquals(mapOf("message_read" to true), normalized)
    }

    @Test
    fun `알 수 없는 null 값도 wire 역직렬화 후 무시함`() {
        val request = requireNotNull(
            objectMapper.readValue(
                """{"permissions":{"future_permission":null}}""",
                UpsertChannelRolePolicyRequest::class.java,
            ),
        )

        assertEquals(mapOf("message_read" to false), schema.normalize(request.permissions))
    }

    @Test
    fun `알려진 키가 없으면 해당 키의 기본값으로 채움`() {
        val normalized = schema.normalize(emptyMap())

        assertEquals(mapOf("message_read" to false), normalized)
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
