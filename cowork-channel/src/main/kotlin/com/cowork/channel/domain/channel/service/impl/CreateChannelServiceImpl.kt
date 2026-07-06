package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelMember
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.event.ChannelMembershipSyncPublisher
import com.cowork.channel.domain.channel.presentation.data.request.CreateChannelRequest
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.CreateChannelService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.meetingNote.service.CreateDefaultMeetingNoteTemplateService
import com.cowork.channel.global.support.afterCommit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class CreateChannelServiceImpl(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val teamPermissionService: TeamPermissionService,
    private val channelMembershipSyncPublisher: ChannelMembershipSyncPublisher,
    private val channelEventPublisher: ChannelEventPublisher,
    private val createDefaultMeetingNoteTemplateService: CreateDefaultMeetingNoteTemplateService,
) : CreateChannelService {

    private fun parseType(value: String): ChannelType {
        val type = try {
            ChannelType.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            throw ExpectedException("유효하지 않은 채널 타입입니다.", HttpStatus.BAD_REQUEST)
        }
        if (type == ChannelType.DM) {
            throw ExpectedException("DM 채널은 DM 전용 API로만 생성할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
        return type
    }

    private fun parseViewType(value: String): ChannelViewType = try {
        ChannelViewType.valueOf(value.uppercase())
    } catch (e: IllegalArgumentException) {
        throw ExpectedException("유효하지 않은 view_type 입니다.", HttpStatus.BAD_REQUEST)
    }

    @Transactional
    override fun execute(userId: Long, request: CreateChannelRequest): ChannelResponse {
        teamPermissionService.requireTeamMember(request.teamId, userId)

        val channel = channelRepository.save(
            Channel(
                teamId = request.teamId,
                name = request.name,
                type = parseType(request.type),
                viewType = parseViewType(request.viewType),
                description = request.description,
                isPrivate = request.isPrivate,
                position = channelRepository.findMaxPositionByTeamId(request.teamId) + 1,
                createdBy = userId,
            ),
        )
        val member = channelMemberRepository.save(ChannelMember(channelId = channel.id, userId = userId))
        if (channel.viewType == ChannelViewType.MEETING_NOTE) {
            createDefaultMeetingNoteTemplateService.createDefaultTemplate(channel)
        }
        afterCommit {
            channelMembershipSyncPublisher.publishChannelSnapshot(channel, listOf(member))
            channelEventPublisher.publishCreated(channel)
        }
        return ChannelResponse.of(channel)
    }
}
