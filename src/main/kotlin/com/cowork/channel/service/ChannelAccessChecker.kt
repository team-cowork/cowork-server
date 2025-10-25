package com.cowork.channel.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import com.cowork.global.error.BusinessException
import com.cowork.team.domain.TeamPermission
import com.cowork.team.service.TeamAccessChecker
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class ChannelAccessChecker(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val teamAccessChecker: TeamAccessChecker,
) {

    fun findChannel(channelId: Long): Channel =
        channelRepository.findByIdOrNull(channelId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "채널을 찾을 수 없습니다.")

    fun canRead(channel: Channel, userId: Long): Boolean {
        val teamMember = runCatching { teamAccessChecker.requireMember(channel.teamId, userId) }.getOrNull()
            ?: return false
        if (!channel.isPrivate) return true
        return channelMemberRepository.existsByChannelIdAndUserId(channel.id, userId) ||
            teamAccessChecker.hasPermission(teamMember, TeamPermission.MANAGE_CHANNELS)
    }

    fun requireReadable(channelId: Long, userId: Long): Channel {
        val channel = findChannel(channelId)
        if (!canRead(channel, userId)) {
            throw BusinessException(HttpStatus.FORBIDDEN, "채널에 접근할 수 없습니다.")
        }
        return channel
    }

    fun requireManageable(channelId: Long, userId: Long): Channel {
        val channel = findChannel(channelId)
        teamAccessChecker.requirePermission(channel.teamId, userId, TeamPermission.MANAGE_CHANNELS)
        return channel
    }

    fun canManage(channel: Channel, userId: Long): Boolean {
        val teamMember = runCatching { teamAccessChecker.requireMember(channel.teamId, userId) }.getOrNull()
            ?: return false
        return teamAccessChecker.hasPermission(teamMember, TeamPermission.MANAGE_CHANNELS)
    }

    fun requireViewType(channel: Channel, vararg viewTypes: ChannelViewType) {
        if (channel.viewType !in viewTypes) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "이 채널에서는 지원하지 않는 기능입니다.")
        }
    }
}
