package com.cowork.channel.domain.channel.service

interface DeleteChannelService {
    fun deleteChannel(userId: Long, channelId: Long)
}
