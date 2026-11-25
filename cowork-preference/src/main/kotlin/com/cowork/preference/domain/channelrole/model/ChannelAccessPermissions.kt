package com.cowork.preference.domain.channelrole.model

import com.cowork.preference.domain.access.effective.model.EffectivePermissionEvaluator
import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.access.permission.PolicyRole

/** preference.channel-access.state.v1로 발행하는 채널 권한 판정. 유효 권한 조회 API와 같은 판정 코드를 쓴다. */
object ChannelAccessPermissions {
    private val KEYS = PermissionKeys.CHANNEL_SCOPED.toList()

    /** [readPrivate]는 채널이 비공개라면 접근할 수 있는지 여부다. 비공개 전환 검사에 쓴다. */
    data class Result(val permissions: Map<String, Boolean>, val readPrivate: Boolean)

    /**
     * [roles]는 기본 역할과 할당 역할이고, [overwrites]는 역할 ID별 채널 덮어쓰기다.
     * 활성 멤버가 아니거나, 채널이 삭제되었거나, 채널 유형을 모르면 모든 권한을 거부한다.
     * 유효 권한 조회 API와 같이 비공개 여부를 모르면 비공개로 판정한다.
     */
    fun resolve(
        roles: List<PolicyRole>,
        overwrites: Map<Long, Map<String, Boolean>>,
        active: Boolean,
        deleted: Boolean,
        isPrivate: Boolean?,
        channelType: String?,
    ): Result {
        val accessPermission = when (channelType) {
            "TEXT" -> PermissionKeys.MESSAGE_READ
            "VOICE" -> PermissionKeys.VOICE_CONNECT
            else -> null
        }
        if (!active || deleted || accessPermission == null) return Result(KEYS.associateWith { false }, false)
        val permissions = EffectivePermissionEvaluator.channel(
            roles,
            overwrites,
            isPrivate = isPrivate != false,
            accessPermission = accessPermission,
            keys = KEYS,
        ).mapValues { it.value.allowed }
        val readPrivate = EffectivePermissionEvaluator.channel(
            roles,
            overwrites,
            isPrivate = true,
            accessPermission = accessPermission,
            keys = listOf(accessPermission),
        ).getValue(accessPermission).allowed
        return Result(permissions, readPrivate)
    }
}
