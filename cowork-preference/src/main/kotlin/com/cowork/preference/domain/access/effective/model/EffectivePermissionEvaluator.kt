package com.cowork.preference.domain.access.effective.model

import com.cowork.preference.domain.access.permission.PermissionDecision
import com.cowork.preference.domain.access.permission.PermissionDecisionReason
import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.access.permission.PolicyRole
import com.cowork.preference.domain.access.permission.RolePolicy
import com.cowork.preference.domain.accountrole.AccountRolePolicy
import com.cowork.preference.domain.channelrole.model.ChannelRolePermissionResolver
import com.cowork.preference.domain.projectrole.model.ProjectPermissions

/** 범위별 정책 입력을 실제 인가 판정과 같은 resolver로 평가한다. */
object EffectivePermissionEvaluator {
    fun account(roles: List<PolicyRole>, keys: List<String>): Map<String, PermissionDecision> =
        keys.associateWith { AccountRolePolicy.explain(roles, it) }

    fun team(roles: List<PolicyRole>, keys: List<String>): Map<String, PermissionDecision> =
        keys.associateWith { RolePolicy.explain(roles, it) }

    /**
     * [overwrites]는 역할 ID별 채널 덮어쓰기다. 비공개 여부를 모르면 비공개로 판정한다.
     * Channel, Chat, Voice 서비스와 같이 [accessPermission] 이외 권한은 채널 접근 권한을 함께 요구한다.
     * Chat과 같이 채팅 작업과 음성 로그 조회는 음성 채널에서도 message_read를 함께 요구한다.
     */
    fun channel(
        roles: List<PolicyRole>,
        overwrites: Map<Long, Map<String, Boolean>>,
        isPrivate: Boolean,
        accessPermission: String,
        keys: List<String>,
    ): Map<String, PermissionDecision> {
        fun explain(key: String) = ChannelRolePermissionResolver.explain(
            roles.map { ChannelRolePermissionResolver.rolePermission(it, overwrites[it.roleId], key) },
            key,
            isPrivate,
        )
        val access = explain(accessPermission)
        val messageRead = if (accessPermission ==
            PermissionKeys.MESSAGE_READ
        ) {
            access
        } else {
            explain(PermissionKeys.MESSAGE_READ)
        }
        return keys.associateWith { key ->
            if (key == accessPermission) return@associateWith access
            val decision = explain(key)
            when {
                !decision.allowed -> decision

                !access.allowed ->
                    decision.copy(allowed = false, decidedBy = PermissionDecisionReason.REQUIRES_CHANNEL_ACCESS)

                key in MESSAGE_READ_DEPENDENT && !messageRead.allowed ->
                    decision.copy(allowed = false, decidedBy = PermissionDecisionReason.REQUIRES_MESSAGE_READ)

                else -> decision
            }
        }
    }

    /**
     * 프로젝트 판정 값은 Project 서비스에 발행된 [stored] 접근 상태를 따른다.
     * 근거는 같은 입력으로 다시 계산하며, 두 값이 다르면 [PermissionDecisionReason.ACCESS_STATE_PENDING]으로 표시한다.
     */
    fun project(
        teamRoles: List<PolicyRole>,
        projectRoles: List<PolicyRole>,
        isPrivate: Boolean,
        stored: ProjectPermissions,
        keys: List<String>,
    ): Map<String, PermissionDecision> {
        val manager = teamRoles.any { it.permissions[PermissionKeys.ADMINISTRATOR] == true }
        val decisions = ProjectPermissions.explain(teamRoles, projectRoles, manager, isPrivate)
        val storedValues = stored.toScopedMap()
        return keys.associateWith { key ->
            val decision = decisions.getValue(key)
            val storedValue = storedValues.getValue(key)
            if (decision.allowed == storedValue) {
                decision
            } else {
                decision.copy(allowed = storedValue, decidedBy = PermissionDecisionReason.ACCESS_STATE_PENDING)
            }
        }
    }

    /** Chat의 `ChannelActionPermission`과 음성 로그 조회. 모두 메시지 읽기 권한을 선행 조건으로 쓴다. */
    private val MESSAGE_READ_DEPENDENT = setOf(
        PermissionKeys.MESSAGE_SEND,
        PermissionKeys.THREAD_REPLY,
        PermissionKeys.FILE_ATTACH,
        PermissionKeys.REACTION_ADD,
        PermissionKeys.MESSAGE_PIN,
        PermissionKeys.MESSAGE_MANAGE,
        PermissionKeys.SLASH_COMMAND_USE,
        PermissionKeys.GITHUB_ISSUE_CREATE,
        PermissionKeys.VOICE_LOG_READ,
    )
}
