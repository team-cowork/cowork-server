package com.cowork.channel.domain.thread.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.thread.presentation.data.response.ThreadResponse
import com.cowork.channel.domain.thread.repository.ThreadRepository
import com.cowork.channel.domain.thread.service.GetThreadsService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetThreadsServiceImpl(
    private val threadRepository: ThreadRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
) : GetThreadsService {

    override fun getThreads(
        userId: Long,
        channelId: Long,
        includeArchived: Boolean,
        pageable: Pageable,
    ): Page<ThreadResponse> {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        teamPermissionService.requireTeamMember(channelAccessGuard.requireTeamChannel(channel), userId)

        val page = if (includeArchived) {
            threadRepository.findByChannelId(channelId, pageable)
        } else {
            threadRepository.findByChannelIdAndIsArchivedFalse(channelId, pageable)
        }
        return page.map { ThreadResponse.of(it) }
    }
}
