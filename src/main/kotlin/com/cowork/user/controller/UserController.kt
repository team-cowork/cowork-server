package com.cowork.user.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.user.dto.UpdateProfileRequest
import com.cowork.user.dto.UserResponse
import com.cowork.user.service.UserService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/users")
class UserController(
    private val userService: UserService,
) {

    @GetMapping("/me")
    fun getMe(@CurrentUser user: AuthUser): UserResponse = userService.getUser(user.id)

    @PatchMapping("/me")
    fun updateMe(
        @CurrentUser user: AuthUser,
        @RequestBody request: UpdateProfileRequest,
    ): UserResponse = userService.updateProfile(user.id, request)

    @GetMapping("/{userId}")
    fun getUser(@PathVariable userId: Long): UserResponse = userService.getUser(userId)
}
