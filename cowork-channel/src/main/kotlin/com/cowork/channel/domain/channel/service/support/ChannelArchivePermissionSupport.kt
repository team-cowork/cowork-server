package com.cowork.channel.domain.channel.service.support

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import com.cowork.channel.domain.project.entity.ProjectAccessProjection
import com.cowork.channel.domain.project.repository.ProjectAccessProjectionRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import com.cowork.channel.global.projection.ProjectionStreams
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

/**
 * 채널 보관·복원 권한. 읽을 수 있는 텍스트 채널만 대상이며, 프로젝트 채널은 해당 프로젝트의 채널 보관 권한을,
 * 프로젝트가 없는 채널은 팀 권한 `archive_channels`를 요구한다. 팀 OWNER와 ADMIN은 허용한다.
 */
@Component
class ChannelArchivePermissionSupport(
    private val channelAccessGuard: ChannelAccessGuard,
    private val permissionEvaluator: ChannelMessageReadPolicyEvaluator,
    private val projectAccessRepository: ProjectAccessProjectionRepository,
    private val projectionReadinessGate: ProjectionReadinessGate,
    private val streams: ProjectionStreams,
) {
    fun requireArchivePermission(channel: Channel, userId: Long) {
        val teamId = channelAccessGuard.requireTeamChannel(channel)
        if (channel.type != ChannelType.TEXT) {
            throw ExpectedException("텍스트 채널만 보관할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
        permissionEvaluator.requireChannelAccess(teamId, userId, channel)
        val projectId = channel.projectId
        if (projectId == null) {
            permissionEvaluator.requireTeamPermission(teamId, userId, ARCHIVE_CHANNELS)
            return
        }
        permissionEvaluator.requireMemberPermission(teamId, userId, "프로젝트 채널 보관 권한이 없습니다.") { membership ->
            projectionReadinessGate.requireCaughtUp(setOf(streams.projectAccess))
            projectAccessRepository.findById(ProjectAccessProjection.key(projectId, userId)).orElse(null)
                ?.allowsChannelArchive(membership.authorizationVersion) == true
        }
    }

    private companion object {
        const val ARCHIVE_CHANNELS = "archive_channels"
    }
}
