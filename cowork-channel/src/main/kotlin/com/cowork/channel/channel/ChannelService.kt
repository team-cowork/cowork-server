package com.cowork.channel.channel

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChannelService(
    private val channelRepository: ChannelRepository,
) {

    @Transactional
    fun listByTeam(teamId: Long): List<ChannelEntity> {
        val existing = channelRepository.findAllByTeamIdOrderByIdAsc(teamId)
        if (existing.isNotEmpty()) return existing

        // Local-first UX: existing teams may not have channels yet. Seed minimal defaults.
        channelRepository.saveAll(
            listOf(
                ChannelEntity(teamId = teamId, type = ChannelType.TEXT, name = "general"),
                ChannelEntity(teamId = teamId, type = ChannelType.VOICE, name = "voice"),
            ),
        )

        return channelRepository.findAllByTeamIdOrderByIdAsc(teamId)
    }
}
