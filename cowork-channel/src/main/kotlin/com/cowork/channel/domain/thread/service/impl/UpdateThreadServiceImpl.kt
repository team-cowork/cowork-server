package com.cowork.channel.domain.thread.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.thread.entity.Thread
import com.cowork.channel.domain.thread.presentation.data.request.UpdateThreadRequest
import com.cowork.channel.domain.thread.presentation.data.response.ThreadResponse
import com.cowork.channel.domain.thread.repository.ThreadRepository
import com.cowork.channel.domain.thread.service.UpdateThreadService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class UpdateThreadServiceImpl(
    private val threadRepository: ThreadRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
) : UpdateThreadService {

    private fun findThreadOrThrow(id: Long): Thread = threadRepository.findById(id).orElseThrow {
        ExpectedException("스레드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
    }

    @Transactional
    override fun updateThread(
        userId: Long,
        channelId: Long,
        threadId: Long,
        request: UpdateThreadRequest,
    ): ThreadResponse {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        val thread = findThreadOrThrow(threadId)
        if (thread.channelId != channelId) {
            throw ExpectedException("해당 채널의 스레드가 아닙니다.", HttpStatus.BAD_REQUEST)
        }

        teamPermissionService.requireChannelAccess(channel, userId)
        if (thread.createdBy != userId) {
            teamPermissionService.requireChannelPermission(channel, userId, "channel_manage")
        }

        thread.update(request.name, request.isArchived)
        return ThreadResponse.of(thread)
    }
}
