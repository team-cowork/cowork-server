package com.cowork.preference.service

import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.repository.ChannelNotificationSettingRepository
import com.cowork.preference.repository.ResourceSettingRepository
import com.cowork.project.event.ProjectDeletedEvent
import com.cowork.team.event.TeamDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class PreferenceLifecycleListener(
    private val resourceSettingRepository: ResourceSettingRepository,
    private val channelNotificationSettingRepository: ChannelNotificationSettingRepository,
) {

    @EventListener
    fun onTeamDeleted(event: TeamDeletedEvent) {
        resourceSettingRepository.deleteByResource(ResourceType.TEAM, event.teamId)
    }

    @EventListener
    fun onProjectDeleted(event: ProjectDeletedEvent) {
        resourceSettingRepository.deleteByResource(ResourceType.PROJECT, event.projectId)
    }

    @EventListener
    fun onChannelDeleted(event: ChannelDeletedEvent) {
        resourceSettingRepository.deleteByResource(ResourceType.TEXT_CHANNEL, event.channelId)
        resourceSettingRepository.deleteByResource(ResourceType.VOICE_CHANNEL, event.channelId)
        channelNotificationSettingRepository.deleteAllByChannelId(event.channelId)
    }
}
