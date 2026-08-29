package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ReorderTeamChannelsService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class ReorderTeamChannelsServiceImpl(
    private val channelRepository: ChannelRepository,
    private val teamPermissionService: TeamPermissionService,
    private val channelEventPublisher: ChannelEventPublisher,
    private val messageReadPolicyEvaluator: ChannelMessageReadPolicyEvaluator,
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

        val lockedChannels = channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(teamId)
        val structurallyVisibleIds = channelRepository
            .findVisibleByTeamIdOrderByPositionAscIdAsc(teamId, userId)
            .mapTo(mutableSetOf()) { it.id }
        val readableChannels = messageReadPolicyEvaluator
            .filterReadable(teamId, userId, lockedChannels.filter { it.id in structurallyVisibleIds })
            .sortedWith(compareBy({ it.position }, { it.id }))
        val readableChannelIds = readableChannels.mapTo(mutableSetOf()) { it.id }
        if (inputIds != readableChannelIds) {
            throw ExpectedException("현재 조회 가능한 모든 채널 ID를 정확히 포함해야 합니다.", HttpStatus.BAD_REQUEST)
        }

        val visiblePositionSlots = readableChannels.map { it.position }.sorted()
        val channelById = readableChannels.associateBy { it.id }
        orderedChannelIds.forEachIndexed { index, channelId ->
            channelById.getValue(channelId).updatePosition(visiblePositionSlots[index])
        }

        val orderedChannels = orderedChannelIds.map(channelById::getValue)
        orderedChannels.forEach(channelEventPublisher::publishUpdated)
        return orderedChannels.map(ChannelResponse::of)
    }
}
