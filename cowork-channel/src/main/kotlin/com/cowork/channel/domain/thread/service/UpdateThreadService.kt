package com.cowork.channel.domain.thread.service

import com.cowork.channel.domain.thread.presentation.data.request.UpdateThreadRequest
import com.cowork.channel.domain.thread.presentation.data.response.ThreadResponse

interface UpdateThreadService {
    fun updateThread(userId: Long, channelId: Long, threadId: Long, request: UpdateThreadRequest): ThreadResponse
}
