package com.cowork.channel.global.client

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@FeignClient(name = "cowork-project")
interface ProjectClient {

    @GetMapping("/projects/{projectId}/team-id")
    fun getTeamId(@PathVariable projectId: Long): Long
}
