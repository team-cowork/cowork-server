package com.cowork.team.domain.teamRole.service.support

import com.cowork.team.global.policy.PermissionKeys
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class TeamRolePermissionSchema {
    fun validate(permissions: Map<String, Any?>): Map<String, Boolean> = permissions.mapValues { (key, value) ->
        if (key !in PermissionKeys.ROLE_SCOPED) {
            throw ExpectedException("지원하지 않는 역할 권한이 포함되어 있습니다.", HttpStatus.BAD_REQUEST)
        }
        value as? Boolean ?: throw ExpectedException("역할 권한 값은 boolean이어야 합니다.", HttpStatus.BAD_REQUEST)
    }

    fun canonicalize(permissions: Map<String, Any?>): Map<String, Boolean> {
        val validated = validate(permissions)
        return PermissionKeys.ROLE_SCOPED.associateWith { validated[it] ?: false }
    }
}
