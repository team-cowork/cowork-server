package com.cowork.channel.controller

import com.cowork.channel.dto.ChannelResponse
import com.cowork.channel.dto.CreateChannelRequest
import com.cowork.channel.dto.UpdateChannelRequest
import com.cowork.channel.service.ChannelService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/channels")
class ChannelController(
    private val channelService: ChannelService,
) {

    @PostMapping
    fun create(
        @CurrentUser user: AuthUser,
        @RequestBody request: CreateChannelRequest,
    ): ChannelResponse = channelService.create(user.id, request)
}
