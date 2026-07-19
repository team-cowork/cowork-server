package com.cowork.channel.domain.thread.service

import com.cowork.channel.domain.thread.presentation.data.response.ThreadResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface GetThreadsService {
    fun getThreads(userId: Long, channelId: Long, includeArchived: Boolean, pageable: Pageable): Page<ThreadResponse>
}
