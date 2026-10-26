package com.cowork.team.domain.teamRole.service.support

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class TeamRolePermissionSchema {
    fun validate(permissions: Map<String, Any?>): Map<String, Boolean> = permissions.mapValues { (key, value) ->
        if (key !in KEYS) {
            throw ExpectedException("지원하지 않는 역할 권한이 포함되어 있습니다.", HttpStatus.BAD_REQUEST)
        }
        value as? Boolean ?: throw ExpectedException("역할 권한 값은 boolean이어야 합니다.", HttpStatus.BAD_REQUEST)
    }

    fun canonicalize(permissions: Map<String, Any?>): Map<String, Boolean> {
        val validated = validate(permissions)
        return KEYS.associateWith { validated[it] ?: false }
    }

    companion object {
        const val MANAGE_ROLES = "manage_roles"
        const val MANAGE_NICKNAMES = "manage_nicknames"

        val KEYS: Set<String> = TeamRolePermission.KEYS
    }
}
