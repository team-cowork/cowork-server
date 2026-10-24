package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ChannelRolePolicyPermissionsTest {

    @Test
    fun `명시한 키만 유지하고 누락된 키는 채우지 않는다`() {
        val canonical = ChannelRolePolicyPermissions.canonicalize(
            JsonObject().put(PermissionKeys.MESSAGE_SEND, false),
        )

        assertEquals(JsonObject().put(PermissionKeys.MESSAGE_SEND, false), canonical)
    }

    @Test
    fun `빈 권한은 거부한다`() {
        assertThrows<IllegalArgumentException> { ChannelRolePolicyPermissions.canonicalize(JsonObject()) }
    }

    @Test
    fun `채널 권한이 아닌 키는 거부한다`() {
        listOf(PermissionKeys.MANAGE_ROLES, "channel_member_manage").forEach { key ->
            assertThrows<IllegalArgumentException> {
                ChannelRolePolicyPermissions.canonicalize(JsonObject().put(key, true))
            }
        }
    }

    @Test
    fun `boolean이 아닌 값은 거부한다`() {
        assertThrows<IllegalArgumentException> {
            ChannelRolePolicyPermissions.canonicalize(JsonObject().put(PermissionKeys.MESSAGE_READ, "yes"))
        }
    }
}
