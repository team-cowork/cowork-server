package com.cowork.preference.domain.access.service

import com.cowork.preference.domain.access.exception.ArchivedChannelException
import com.cowork.preference.domain.access.exception.ProjectionNotReadyException
import com.cowork.preference.domain.access.exception.ResourceAccessDeniedException
import com.cowork.preference.domain.access.projection.channel.model.ChannelLifecycleProjection
import com.cowork.preference.domain.access.projection.channel.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.domain.access.projection.dm.repository.DmParticipantProjectionRepository
import com.cowork.preference.domain.access.projection.team.repository.TeamMemberProjectionRepository
import com.cowork.preference.domain.channelrole.repository.ChannelRolePolicyRepository
import com.cowork.preference.global.projection.readiness.ProjectionReadinessView
import com.cowork.preference.global.security.RequesterContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient

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
    suspend fun requireChannelAccess(channelId: Long, accountId: Long) =
        requireChannelAccess(pool, channelId, accountId)

    suspend fun requireChannelAccess(client: SqlClient, channelId: Long, accountId: Long) {
        if (!accessReadiness.isReady) {
            throw ProjectionNotReadyException("channel or team member projection is not ready")
        }
        val channel = channelRepository.findForRead(client, channelId)?.takeUnless { it.deleted }
            ?: throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        val teamId = channel.teamId
        if (teamId == null) {
            requireDmParticipant(client, channelId, accountId)
            return
        }
        val teamMember = teamMemberRepository.findForRead(client, teamId, accountId)
        if (teamMember == null || teamMember.deleted) throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        val permission = when (channel.channelType) {
            "VOICE" -> "voice_connect"
            "TEXT" -> "message_read"
            else -> throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        }
        if (!policyRepository.canAccess(client, teamId, accountId, channelId, permission, channel.isPrivate)) {
            throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        }
    }

    /**
     * 유효 권한 조회 조건이다. 팀 채널은 소속 팀의 활성 멤버, DM은 대화 참여자만 통과한다.
     * 접근 권한이 없는 멤버도 거부 사유를 확인할 수 있도록 채널 접근 권한은 요구하지 않는다.
     */
    suspend fun requireChannelTeamMember(channelId: Long, accountId: Long): ChannelLifecycleProjection {
        if (!accessReadiness.isReady) {
            throw ProjectionNotReadyException("channel or team member projection is not ready")
        }
        val channel = channelRepository.findForRead(pool, channelId)?.takeUnless { it.deleted }
            ?: throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        val teamId = channel.teamId
        if (teamId == null) {
            requireDmParticipant(pool, channelId, accountId)
            return channel
        }
        val member = teamMemberRepository.findForRead(pool, teamId, accountId)
        if (member == null || member.deleted) throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        return channel
    }

    /** 채널 설정 수정은 접근 권한과 channel_manage가 모두 필요하다. */
    suspend fun requireSettingsManager(channelId: Long, requester: RequesterContext) =
        requireSettingsManager(pool, channelId, requester)

    suspend fun requireSettingsManager(client: SqlClient, channelId: Long, requester: RequesterContext) {
        if (!managerReadiness.isReady) {
            throw ProjectionNotReadyException("channel lifecycle or team member projection is not ready")
        }
        val channel = channelRepository.findForRead(client, channelId)?.takeUnless { it.deleted }
            ?: throw ResourceAccessDeniedException(MANAGER_DENIED)
        val teamId = channel.teamId ?: throw ResourceAccessDeniedException(MANAGER_DENIED)
        val member = teamMemberRepository.findForRead(client, teamId, requester.userId)
        if (member == null || member.deleted) throw ResourceAccessDeniedException(MANAGER_DENIED)
        requireChannelAccess(client, channelId, requester.userId)
        if (!policyRepository.canAccess(
                client,
                teamId,
                requester.userId,
                channelId,
                "channel_manage",
                channel.isPrivate,
            )
        ) {
            throw ResourceAccessDeniedException(MANAGER_DENIED)
        }
    }

    /** 보관된 채널은 읽기 전용이므로 설정 변경을 거부한다. */
    suspend fun requireNotArchived(channelId: Long) {
        if (channelRepository.findForRead(pool, channelId)?.archived == true) throw ArchivedChannelException()
    }

    private suspend fun requireDmParticipant(client: SqlClient, channelId: Long, accountId: Long) {
        if (!dmParticipantRepository.isActiveParticipant(client, channelId, accountId)) {
            throw ResourceAccessDeniedException(CHANNEL_ACCESS_DENIED)
        }
    }

    companion object {
        const val CHANNEL_ACCESS_DENIED = "채널에 접근할 권한이 없습니다."
        private const val MANAGER_DENIED = "채널 설정을 관리할 권한이 없습니다."
    }
}
