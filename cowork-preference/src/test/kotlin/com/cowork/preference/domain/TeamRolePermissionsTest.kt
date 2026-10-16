package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TeamRolePermissionsTest {

    @Test
    fun `누락된 키를 false로 채운 전체 권한을 만든다`() {
        val canonical = TeamRolePermissions.canonicalize(mapOf(PermissionKeys.MANAGE_ROLES to true))

        assertEquals(PermissionKeys.ROLE_SCOPED, canonical.keys)
        assertTrue(canonical.getValue(PermissionKeys.MANAGE_ROLES))
        assertFalse(canonical.getValue(PermissionKeys.MESSAGE_SEND))
    }

    @Test
    fun `변경한 키만 덮어쓰고 나머지 권한은 유지한다`() {
        val current = TeamRolePermissions.canonicalize(
            mapOf(PermissionKeys.MANAGE_ROLES to true, PermissionKeys.MESSAGE_SEND to true),
        )

        val merged = TeamRolePermissions.merge(current, mapOf(PermissionKeys.MESSAGE_SEND to false))

        assertTrue(merged.getValue(PermissionKeys.MANAGE_ROLES))
        assertFalse(merged.getValue(PermissionKeys.MESSAGE_SEND))
    }

    @Test
    fun `저장된 권한에서 알 수 없는 키는 버리고 누락된 키는 false로 읽는다`() {
        val stored = JsonObject().put(PermissionKeys.FILE_ATTACH, true).put("removed_permission", true)

        val permissions = TeamRolePermissions.fromStored(stored)

        assertEquals(PermissionKeys.ROLE_SCOPED, permissions.keys)
        assertTrue(permissions.getValue(PermissionKeys.FILE_ATTACH))
        assertFalse(permissions.getValue(PermissionKeys.MANAGE_TEAM))
    }

    @Test
    fun `알 수 없는 키나 boolean이 아닌 값은 거부한다`() {
        assertThrows<IllegalArgumentException> { TeamRolePermissions.validate(mapOf("MANAGE_ROLES" to true)) }
        assertThrows<IllegalArgumentException> { TeamRolePermissions.validate(mapOf(PermissionKeys.MANAGE_ROLES to 1)) }
    }
}
