package com.cowork.channel.client

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@FeignClient(name = "cowork-team")
interface TeamClient {

    @GetMapping("/teams/{teamId}/members")
    fun getMembers(@PathVariable teamId: Long): List<TeamMemberDto>
}

data class TeamMemberDto(
    val id: Long,
    val userId: Long,
    val role: String,
)
