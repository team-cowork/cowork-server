package com.cowork.project.global.client

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader

@FeignClient(name = "cowork-channel")
interface ChannelClient {

    @GetMapping("/channels/{channelId}")
    fun getChannel(@RequestHeader("X-User-Id") userId: Long, @PathVariable channelId: Long): ChannelResDto
}

data class ChannelResDto(val id: Long, val projectId: Long?)
