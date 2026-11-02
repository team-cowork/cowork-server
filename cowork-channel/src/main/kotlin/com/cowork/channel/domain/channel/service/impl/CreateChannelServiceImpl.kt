package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.event.InitialChannelRoleOverwrite
import com.cowork.channel.domain.channel.presentation.data.request.CreateChannelRequest
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.CreateChannelService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.support.ChannelRolePolicyPermissionSchema
import com.cowork.channel.domain.meetingNote.service.CreateDefaultMeetingNoteTemplateService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class CreateChannelServiceImpl(
    private val channelRepository: ChannelRepository,
    private val teamPermissionService: TeamPermissionService,
    private val channelEventPublisher: ChannelEventPublisher,
    private val createDefaultMeetingNoteTemplateService: CreateDefaultMeetingNoteTemplateService,
    private val permissionSchema: ChannelRolePolicyPermissionSchema,
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

    /**
     * 재정렬과 같은 팀 채널 행 잠금 아래에서 다음 position을 정한다. READ COMMITTED로 실행해 빈 팀에서도 gap 잠금 교착 없이
     * 동시 생성 경합을 (team_id, position) 유일성 제약 충돌로 끝낸다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    override fun execute(userId: Long, request: CreateChannelRequest): ChannelResponse {
        teamPermissionService.requirePermission(request.teamId, userId, "manage_channels")
        val lockedChannels = channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(request.teamId)

        val proposedChannel = Channel(
            teamId = request.teamId,
            name = request.name,
            type = parseType(request.type),
            viewType = parseViewType(request.viewType),
            description = request.description,
            isPrivate = request.isPrivate,
            position = (lockedChannels.maxOfOrNull { it.position } ?: -1) + 1,
            createdBy = userId,
        )
        val initialOverwrites = request.initialRoleOverwrites.map {
            InitialChannelRoleOverwrite(it.roleId, permissionSchema.normalize(it.permissions))
        }
        val seed = if (proposedChannel.isPrivate || initialOverwrites.isNotEmpty()) {
            teamPermissionService.prepareInitialRolePolicySeed(proposedChannel, userId, initialOverwrites)
        } else {
            null
        }
        val channel = try {
            channelRepository.save(proposedChannel)
        } catch (e: DataIntegrityViolationException) {
            throw ExpectedException("다른 요청과 채널 순서가 충돌했습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT)
        }
        if (channel.viewType == ChannelViewType.MEETING_NOTE) {
            createDefaultMeetingNoteTemplateService.createDefaultTemplate(channel)
        }
        channelEventPublisher.publishCreated(channel, initialRolePolicySeed = seed)
        return ChannelResponse.of(channel)
    }
}
