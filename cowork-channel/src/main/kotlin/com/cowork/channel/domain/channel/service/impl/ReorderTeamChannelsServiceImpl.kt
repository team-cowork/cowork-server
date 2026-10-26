package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ReorderTeamChannelsService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class ReorderTeamChannelsServiceImpl(
    private val channelRepository: ChannelRepository,
    private val teamPermissionService: TeamPermissionService,
    private val channelEventPublisher: ChannelEventPublisher,
    private val messageReadPolicyEvaluator: ChannelMessageReadPolicyEvaluator,
) : ReorderTeamChannelsService {

    /** 채널 생성과 같은 팀 채널 행 잠금으로 직렬화하고, 생성과 같은 이유로 READ COMMITTED로 실행한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
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
        val readableChannels = messageReadPolicyEvaluator
            .filterReadable(teamId, userId, lockedChannels)
            .sortedWith(compareBy({ it.position }, { it.id }))
        val readableChannelIds = readableChannels.mapTo(mutableSetOf()) { it.id }
        if (inputIds != readableChannelIds) {
            throw ExpectedException("현재 조회 가능한 모든 채널 ID를 정확히 포함해야 합니다.", HttpStatus.BAD_REQUEST)
        }

        val visiblePositionSlots = readableChannels.map { it.position }.sorted()
        val channelById = readableChannels.associateBy { it.id }
        val movedChannels = orderedChannelIds.mapIndexedNotNull { index, channelId ->
            channelById.getValue(channelId).takeIf { it.position != visiblePositionSlots[index] }
        }
        messageReadPolicyEvaluator.requireReorder(teamId, userId, movedChannels)
        // (team_id, position) 유일성 제약이 행 단위로 검사되므로 이동할 채널을 음수 임시 position으로 먼저 비운다.
        movedChannels.forEachIndexed { index, channel -> channel.updatePosition(-1 - index) }
        channelRepository.flush()
        orderedChannelIds.forEachIndexed { index, channelId ->
            channelById.getValue(channelId).updatePosition(visiblePositionSlots[index])
        }

        val orderedChannels = orderedChannelIds.map(channelById::getValue)
        movedChannels.forEach(channelEventPublisher::publishUpdated)
        return orderedChannels.map(ChannelResponse::of)
    }
}
