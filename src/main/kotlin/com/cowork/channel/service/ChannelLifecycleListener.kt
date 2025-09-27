package com.cowork.channel.service

import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import com.cowork.project.event.ProjectDeletedEvent
import com.cowork.team.event.TeamDeletedEvent
import com.cowork.team.event.TeamMemberRemovedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ChannelLifecycleListener(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelService: ChannelService,
) {

    @EventListener
    fun onTeamDeleted(event: TeamDeletedEvent) {
        channelRepository.findAllByTeamIdOrderByPositionAscIdAsc(event.teamId)
            .forEach(channelService::deleteChannel)
    }

    @EventListener
    fun onTeamMemberRemoved(event: TeamMemberRemovedEvent) {
        val channelIds = channelRepository.findIdsByTeamId(event.teamId)
        if (channelIds.isEmpty()) return
        channelMemberRepository.deleteAllByUserIdAndChannelIdIn(event.userId, channelIds)
    }

    @EventListener
    fun onProjectDeleted(event: ProjectDeletedEvent) {
        channelRepository.unassignProject(event.projectId)
    }
}
