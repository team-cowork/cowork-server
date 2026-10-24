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

        val KEYS: Set<String> = linkedSetOf(
            "manage_team",
            MANAGE_ROLES,
            "manage_invites",
            "kick_members",
            "manage_channels",
            "manage_projects",
            "manage_github",
            "message_read",
            "message_send",
            "thread_reply",
            "file_attach",
            "reaction_add",
            "message_pin",
            "message_manage",
            "slash_command_use",
            "github_issue_create",
            "channel_manage",
            "channel_reorder",
            // TODO: 임시 호환 키이며 멤버 관리 기능은 없다.
            // 저장된 역할과 보관 이벤트에서 이 키를 제거하고 생산자·소비자를 함께 전환한 뒤 삭제한다.
            "channel_member_manage",
            "voice_connect",
            "voice_speak",
            "screen_share",
            "live_broadcast",
        )
    }
}
