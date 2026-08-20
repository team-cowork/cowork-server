package com.cowork.channel.domain.channel.presentation.controller

import com.cowork.channel.domain.channel.presentation.data.response.ChannelMembershipResponse
import com.cowork.channel.domain.channel.service.VerifyChannelMembershipService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Gateway를 거치지 않는 서비스 간(cowork-voice 등) 내부 호출 전용 API. */
@RestController
@RequestMapping("/internal/channels")
class InternalChannelController(private val verifyChannelMembershipService: VerifyChannelMembershipService) {

    @GetMapping("/{channelId}/members/{userId}")
    fun verifyMembership(@PathVariable channelId: Long, @PathVariable userId: Long): ChannelMembershipResponse =
        verifyChannelMembershipService.execute(channelId, userId)
}
