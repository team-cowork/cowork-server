package com.cowork.preference.service

import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.DmParticipantProjectionRepository
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.security.RequesterContext
import io.vertx.sqlclient.Pool

/**
 * 채널 범위 Preference API의 접근 권한을 cowork-channel의 `ChannelPermissionSupport` 기준으로 검증한다.
 * 채널 소유 서비스와 같이 전역 ADMIN 우회는 없다. 채널 소속 팀·공개 여부는 `channel.event.v2`,
 * 채널 멤버십은 `channel.dm-participant.state.v1`, 팀 역할은 `team.member.event` projection을 읽는다.
 */
class ChannelAccessGuard(
    private val pool: Pool,
    private val channelRepository: ChannelLifecycleProjectionRepository,
    private val dmParticipantRepository: DmParticipantProjectionRepository,
    private val teamMemberRepository: TeamMemberProjectionRepository,
    private val policyRepository: ChannelRolePolicyRepository,
    /** 팀 멤버·채널 lifecycle·채널 멤버 projection이 모두 준비되었을 때만 열린다. */
    private val accessReadiness: ProjectionReadinessView,
    /** 팀 멤버·채널 lifecycle projection이 모두 준비되었을 때만 열린다. */
    private val managerReadiness: ProjectionReadinessView,
) {
    /** 팀 채널은 역할 권한, DM은 대화 참여자로 접근을 판단한다. */
    suspend fun requireChannelAccess(channelId: Long, accountId: Long) {
        if (!accessReadiness.isReady) {
            throw ProjectionNotReadyException("channel or team member projection is not ready")
        }
        val channel = channelRepository.findForRead(pool, channelId)?.takeUnless { it.deleted }
            ?: throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        val teamId = channel.teamId
        if (teamId == null) {
            requireDmParticipant(channelId, accountId)
            return
        }
        val teamMember = teamMemberRepository.findForRead(pool, teamId, accountId)
        if (teamMember == null || teamMember.deleted) throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        val permission = when (channel.channelType) {
            "VOICE" -> "voice_connect"
            "TEXT" -> "message_read"
            else -> throw ProjectionNotReadyException("team channel type is not known yet")
        }
        if (teamMember.builtInRole !in MANAGER_ROLES && !policyRepository.canAccess(teamId, accountId, channelId, permission, channel.isPrivate != false)) {
            throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        }
    }

    /** 채널 설정 수정은 접근 권한과 channel_manage가 모두 필요하다. */
    suspend fun requireSettingsManager(channelId: Long, requester: RequesterContext) {
        if (!managerReadiness.isReady) {
            throw ProjectionNotReadyException("channel lifecycle or team member projection is not ready")
        }
        val channel = channelRepository.findForRead(pool, channelId)?.takeUnless { it.deleted }
            ?: throw ResourceAccessDeniedException(MANAGER_DENIED)
        val teamId = channel.teamId ?: throw ResourceAccessDeniedException(MANAGER_DENIED)
        val member = teamMemberRepository.findForRead(pool, teamId, requester.userId)
        if (member == null || member.deleted) throw ResourceAccessDeniedException(MANAGER_DENIED)
        if (member.builtInRole in MANAGER_ROLES) return
        requireChannelAccess(channelId, requester.userId)
        if (!policyRepository.canAccess(teamId, requester.userId, channelId, "channel_manage", channel.isPrivate != false)) {
            throw ResourceAccessDeniedException(MANAGER_DENIED)
        }
    }

    private suspend fun requireDmParticipant(channelId: Long, accountId: Long) {
        if (!dmParticipantRepository.isActiveParticipant(pool, channelId, accountId)) {
            throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        }
    }

    private companion object {
        val MANAGER_ROLES = setOf("OWNER", "ADMIN")
        const val CHANNEL_ACCESS_DENIED = "채널에 접근할 권한이 없습니다."
        const val MANAGER_DENIED = "채널 설정을 관리할 권한이 없습니다."
    }
}
