package com.cowork.preference.service

import com.cowork.channel.domain.ChannelType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.global.error.BusinessException
import com.cowork.preference.domain.ResourceType
import com.cowork.project.service.ProjectAccessChecker
import com.cowork.team.domain.TeamPermission
import com.cowork.team.service.TeamAccessChecker
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class PreferenceAccessPolicy(
    private val teamAccessChecker: TeamAccessChecker,
    private val projectAccessChecker: ProjectAccessChecker,
    private val channelAccessChecker: ChannelAccessChecker,
) {

    fun requireReadable(type: ResourceType, resourceId: Long, userId: Long) {
        when (type) {
            ResourceType.ACCOUNT -> Unit
            ResourceType.TEAM -> teamAccessChecker.requireMember(resourceId, userId)
            ResourceType.PROJECT -> projectAccessChecker.requireReadable(resourceId, userId)
            ResourceType.TEXT_CHANNEL -> requireChannelType(channelAccessChecker.requireReadable(resourceId, userId).type, ChannelType.TEXT)
            ResourceType.VOICE_CHANNEL -> requireChannelType(channelAccessChecker.requireReadable(resourceId, userId).type, ChannelType.VOICE)
        }
    }

    fun requireWritable(type: ResourceType, resourceId: Long, userId: Long) {
        when (type) {
            ResourceType.ACCOUNT -> if (resourceId != userId) {
                throw BusinessException(HttpStatus.FORBIDDEN, "본인의 설정만 변경할 수 있습니다.")
            }
            ResourceType.TEAM -> teamAccessChecker.requirePermission(resourceId, userId, TeamPermission.MANAGE_TEAM)
            ResourceType.PROJECT -> projectAccessChecker.requireEditable(resourceId, userId)
            ResourceType.TEXT_CHANNEL -> requireChannelType(channelAccessChecker.requireManageable(resourceId, userId).type, ChannelType.TEXT)
            ResourceType.VOICE_CHANNEL -> requireChannelType(channelAccessChecker.requireManageable(resourceId, userId).type, ChannelType.VOICE)
        }
    }

    private fun requireChannelType(actual: ChannelType, expected: ChannelType) {
        if (actual != expected) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "채널 종류가 설정 유형과 일치하지 않습니다.")
        }
    }
}
