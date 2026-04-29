package com.cowork.project.client

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@FeignClient(name = "cowork-team", fallbackFactory = TeamClientFallbackFactory::class)
interface TeamClient {

    @GetMapping("/teams/{teamId}/members/{userId}")
    fun getMembership(@PathVariable teamId: Long, @PathVariable userId: Long): TeamMembershipResponse
}
