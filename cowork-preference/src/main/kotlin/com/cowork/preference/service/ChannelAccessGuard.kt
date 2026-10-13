package com.cowork.preference.service

import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.ChannelMemberProjectionRepository
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.security.RequesterContext
import io.vertx.sqlclient.Pool

/**
 * 채널 범위 Preference API의 접근 권한을 cowork-channel의 `ChannelPermissionSupport` 기준으로 검증한다.
 * 채널 소유 서비스와 같이 전역 ADMIN 우회는 없다. 채널 소속 팀·공개 여부는 `channel.event.v2`,
 * 채널 멤버십은 `channel.member.event.v2`, 팀 역할은 `team.member.event` projection을 읽는다.
 */
class ChannelAccessGuard(
    private val pool: Pool,
    private val channelRepository: ChannelLifecycleProjectionRepository,
    private val channelMemberRepository: ChannelMemberProjectionRepository,
    private val teamMemberRepository: TeamMemberProjectionRepository,
    private val policyRepository: ChannelRolePolicyRepository,
    /** 팀 멤버·채널 lifecycle·채널 멤버 projection이 모두 준비되었을 때만 열린다. */
    private val accessReadiness: ProjectionReadinessView,
    /** 팀 멤버·채널 lifecycle projection이 모두 준비되었을 때만 열린다. */
    private val managerReadiness: ProjectionReadinessView,
) {
    /**
     * 채널 설정 조회·계정별 채널 알림: 팀 채널은 팀 멤버이고, 채널 멤버가 아니면 공개 채널이어야 하며,
     * 팀 OWNER가 아니면 역할 정책으로 메시지 읽기가 허용되어야 한다. DM 채널(팀 없음)은 채널 멤버여야 한다.
     * 팀 멤버이지만 채널 멤버가 아닌데 공개 여부(`isPrivate`)를 아직 알 수 없으면 공개로 가정하지 않고
     * projection이 따라잡는 중으로 보아 [ProjectionNotReadyException]을 던진다.
     */
    suspend fun requireChannelAccess(channelId: Long, accountId: Long) {
        if (!accessReadiness.isReady) {
            throw ProjectionNotReadyException("channel or team member projection is not ready")
        }
        val channel = channelRepository.findForRead(pool, channelId)?.takeUnless { it.deleted }
            ?: throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        val teamId = channel.teamId
        if (teamId == null) {
            requireChannelMember(channelId, accountId)
            return
        }
        val teamMember = teamMemberRepository.findForRead(pool, teamId, accountId)
        if (teamMember == null || teamMember.deleted) throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        if (!channelMemberRepository.isActiveMember(pool, channelId, accountId)) {
            when (channel.isPrivate) {
                true -> throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
                null -> throw ProjectionNotReadyException("channel visibility is not known yet")
                false -> Unit
            }
        }
        if (teamMember.builtInRole != OWNER_ROLE && !policyRepository.canReadMessages(teamId, accountId, channelId)) {
            throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        }
    }

    /**
     * 채널 설정 수정: 채널이 속한 팀의 OWNER/ADMIN.
     * 채널 소유 서비스는 채널 생성자도 관리자로 보지만 채널 이벤트에 생성자가 없어 이 서비스에서는 판단할 수 없다.
     * 삭제되었거나 알 수 없는 채널, 팀이 없는 DM 채널은 거부한다.
     */
    suspend fun requireSettingsManager(channelId: Long, requester: RequesterContext) {
        if (!managerReadiness.isReady) {
            throw ProjectionNotReadyException("channel lifecycle or team member projection is not ready")
        }
        val teamId = channelRepository.findForRead(pool, channelId)?.takeUnless { it.deleted }?.teamId
            ?: throw ResourceAccessDeniedException(MANAGER_DENIED)
        val member = teamMemberRepository.findForRead(pool, teamId, requester.userId)
        if (member == null || member.deleted || member.builtInRole !in MANAGER_ROLES) {
            throw ResourceAccessDeniedException(MANAGER_DENIED)
        }
    }

    private suspend fun requireChannelMember(channelId: Long, accountId: Long) {
        if (!channelMemberRepository.isActiveMember(pool, channelId, accountId)) {
            throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        }
    }

    private companion object {
        const val OWNER_ROLE = "OWNER"
        val MANAGER_ROLES = setOf("OWNER", "ADMIN")
        const val CHANNEL_ACCESS_DENIED = "채널에 접근할 권한이 없습니다."
        const val MANAGER_DENIED = "채널 설정을 관리할 권한이 없습니다."
    }
}
