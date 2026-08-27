package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ReorderTeamChannelsService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class ReorderTeamChannelsServiceImpl(
    private val channelRepository: ChannelRepository,
    private val teamPermissionService: TeamPermissionService,
    private val channelEventPublisher: ChannelEventPublisher,
) : ReorderTeamChannelsService {

    @Transactional
    override fun execute(userId: Long, teamId: Long, orderedChannelIds: List<Long>): List<ChannelResponse> {
        teamPermissionService.requireTeamMember(teamId, userId)

        if (orderedChannelIds.isEmpty()) {
            throw ExpectedException("채널 순서 목록은 비어 있을 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        val inputIds = orderedChannelIds.toSet()
        if (inputIds.size != orderedChannelIds.size) {
            throw ExpectedException("채널 순서 목록에 중복 ID가 포함되어 있습니다.", HttpStatus.BAD_REQUEST)
        }

        val channels = channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(teamId)
        val teamChannelIds = channels.map { it.id }.toSet()
        if (inputIds != teamChannelIds) {
            throw ExpectedException("팀의 모든 채널 ID를 정확히 포함해야 합니다.", HttpStatus.BAD_REQUEST)
        }

        val channelById = channels.associateBy { it.id }
        orderedChannelIds.forEachIndexed { index, channelId ->
            channelById[channelId]?.updatePosition(index)
        }

        val orderedChannels = orderedChannelIds.mapNotNull(channelById::get)
        orderedChannels.forEach(channelEventPublisher::publishUpdated)
        return orderedChannels.map(ChannelResponse::of)
    }
}
