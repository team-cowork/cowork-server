package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.presentation.data.response.ChannelMemberResponse
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.GetChannelMembersService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetChannelMembersServiceImpl(
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val channelPermissionSupport: ChannelPermissionSupport,
) : GetChannelMembersService {

    override fun getMembers(userId: Long, channelId: Long): List<ChannelMemberResponse> {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        channelPermissionSupport.requireChannelAccess(channel, userId)
        return channelMemberRepository.findByChannelId(channelId).map { ChannelMemberResponse.of(it) }
    }
}
