package com.cowork.channel.domain.channel.service

interface DeleteChannelService {
    fun execute(userId: Long, channelId: Long)
}
