package com.cowork.channel.service

import com.cowork.channel.domain.Thread
import com.cowork.channel.dto.CreateThreadRequest
import com.cowork.channel.dto.ThreadResponse
import com.cowork.channel.dto.UpdateThreadRequest
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ThreadRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional(readOnly = true)
class ThreadService(
    private val threadRepository: ThreadRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelService: ChannelService,
    private val teamPermissionService: TeamPermissionService,
) {

    private fun findThreadOrThrow(id: Long): Thread =
        threadRepository.findById(id).orElseThrow {
            ExpectedException("스레드를 찾을 수 없습니다. id=$id", HttpStatus.NOT_FOUND)
        }

    @Transactional
    fun createThread(userId: Long, channelId: Long, request: CreateThreadRequest): ThreadResponse {
        channelService.findChannelOrThrow(channelId)
        if (!channelMemberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw ExpectedException("채널 멤버만 스레드를 생성할 수 있습니다.", HttpStatus.FORBIDDEN)
        }

        val thread = threadRepository.save(
            Thread(
                channelId = channelId,
                name = request.name,
                parentMessageId = request.parentMessageId,
                createdBy = userId,
            )
        )
        return ThreadResponse.of(thread)
    }

    fun getThreads(userId: Long, channelId: Long, includeArchived: Boolean, pageable: Pageable): Page<ThreadResponse> {
        val channel = channelService.findChannelOrThrow(channelId)
        teamPermissionService.requireTeamMember(channel.teamId, userId)

        val page = if (includeArchived) {
            threadRepository.findByChannelId(channelId, pageable)
        } else {
            threadRepository.findByChannelIdAndIsArchivedFalse(channelId, pageable)
        }
        return page.map { ThreadResponse.of(it) }
    }

    @Transactional
    fun updateThread(userId: Long, channelId: Long, threadId: Long, request: UpdateThreadRequest): ThreadResponse {
        val channel = channelService.findChannelOrThrow(channelId)
        val thread = findThreadOrThrow(threadId)
        if (thread.channelId != channelId) {
            throw ExpectedException("해당 채널의 스레드가 아닙니다.", HttpStatus.BAD_REQUEST)
        }

        val isThreadCreator = thread.createdBy == userId
        val isChannelCreator = channel.createdBy == userId
        val isTeamManager = teamPermissionService.isTeamOwnerOrAdmin(channel.teamId, userId)

        if (!isThreadCreator && !isChannelCreator && !isTeamManager) {
            throw ExpectedException("스레드 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }

        thread.update(request.name, request.isArchived)
        return ThreadResponse.of(thread)
    }
}
