package com.cowork.channel.client

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@FeignClient(name = "cowork-team")
interface TeamClient {

    @GetMapping("/teams/{teamId}/members/{userId}/exists")
    fun isMember(@PathVariable teamId: Long, @PathVariable userId: Long): Map<String, Boolean>
}
