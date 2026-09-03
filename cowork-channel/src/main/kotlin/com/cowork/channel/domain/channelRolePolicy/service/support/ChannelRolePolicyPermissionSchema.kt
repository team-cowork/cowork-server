package com.cowork.channel.domain.channelRolePolicy.service.support

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class ChannelRolePolicyPermissionSchema {
    fun normalize(permissions: Map<String, Any?>): Map<String, Boolean> = DEFAULTS.mapValues { (key, defaultValue) ->
        if (!permissions.containsKey(key)) return@mapValues defaultValue
        val value = permissions[key]
        if (value !is Boolean) {
            throw ExpectedException(
                "permissions.$key 값은 boolean이어야 합니다.",
                HttpStatus.BAD_REQUEST,
            )
        }
        value
    }

    private companion object {
        val DEFAULTS = linkedMapOf(
            "message_read" to false,
        )
    }
}
