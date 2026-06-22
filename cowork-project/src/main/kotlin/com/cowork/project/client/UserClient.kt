package com.cowork.project.client

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@FeignClient(name = "cowork-user")
interface UserClient {

    @GetMapping("/users/{userId}")
    fun getUserProfile(@PathVariable userId: Long): UserProfileResponse
}

data class UserProfileResponse(
    @JsonProperty("github_id")
    val githubId: String?,
)
