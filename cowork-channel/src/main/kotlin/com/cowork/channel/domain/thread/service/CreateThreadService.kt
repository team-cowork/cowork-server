package com.cowork.channel.domain.thread.service

import com.cowork.channel.domain.thread.presentation.data.request.CreateThreadRequest
import com.cowork.channel.domain.thread.presentation.data.response.ThreadResponse

interface CreateThreadService {
    fun createThread(userId: Long, channelId: Long, request: CreateThreadRequest): ThreadResponse
}
