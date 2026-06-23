package com.cowork.project.domain.client

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@FeignClient(name = "cowork-user")
interface UserClient {

    @GetMapping("/users/{userId}")
    fun getUserProfile(@PathVariable userId: Long): UserProfileResDto
}

data class UserProfileResDto(
    @field:JsonProperty("github_id")
    val githubId: String?,
)
