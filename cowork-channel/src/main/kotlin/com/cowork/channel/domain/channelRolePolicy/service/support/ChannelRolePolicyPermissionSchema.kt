package com.cowork.channel.domain.channelRolePolicy.service.support

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

/**
 * 채널별 역할 권한 덮어쓰기 스키마. 보낸 키만 명시적 허용·거부로 저장하고, 누락된 키는 역할 기본 권한을 따른다.
 * 알 수 없는 키는 값 타입과 무관하게 무시한다.
 */
@Component
class ChannelRolePolicyPermissionSchema {
    fun normalize(permissions: Map<String, Any?>): Map<String, Boolean> {
        val normalized = KEYS.filter(permissions::containsKey).associateWith { key ->
            permissions[key] as? Boolean
                ?: throw ExpectedException("permissions.$key 값은 boolean이어야 합니다.", HttpStatus.BAD_REQUEST)
        }
        if (normalized.isEmpty()) {
            throw ExpectedException("permissions에는 지원하는 권한이 하나 이상 필요합니다.", HttpStatus.BAD_REQUEST)
        }
        return normalized
    }

    companion object {
        const val MESSAGE_READ = "message_read"

        val KEYS: Set<String> = linkedSetOf(
            MESSAGE_READ,
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
            "slowmode_bypass",
            "voice_connect",
            "voice_speak",
            "screen_share",
            "live_broadcast",
        )
    }
}
