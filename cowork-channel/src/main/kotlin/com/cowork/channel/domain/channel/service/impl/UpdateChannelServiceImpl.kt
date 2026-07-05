package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.presentation.data.request.UpdateChannelRequest
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.UpdateChannelService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.global.support.afterCommit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class UpdateChannelServiceImpl(
    private val channelEventPublisher: ChannelEventPublisher,
    private val channelAccessGuard: ChannelAccessGuard,
    private val channelPermissionSupport: ChannelPermissionSupport,
) : UpdateChannelService {

    override fun updateChannel(
        userId: Long,
        channelId: Long,
        request: UpdateChannelRequest,
        updateProjectId: Boolean,
    ): ChannelResponse {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        channelAccessGuard.requireTeamChannel(channel)
        channelPermissionSupport.requireChannelManager(channel, userId)
        channel.update(request.name, request.description, request.isPrivate)
        if (updateProjectId) channel.assignProject(request.projectId)
        afterCommit { channelEventPublisher.publishUpdated(channel) }
        return ChannelResponse.of(channel)
    }
}
