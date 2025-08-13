package com.cowork.user.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.user.dto.UpdateProfileRequest
import com.cowork.user.dto.UserResponse
import com.cowork.user.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "사용자")
@RestController
@RequestMapping("/users")
class UserController(
    private val userService: UserService,
) {

    @Operation(summary = "내 정보 조회")
    @GetMapping("/me")
    fun getMe(@CurrentUser user: AuthUser): UserResponse = userService.getUser(user.id)

    @Operation(summary = "내 프로필 수정")
    @PatchMapping("/me")
    fun updateMe(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: UpdateProfileRequest,
    ): UserResponse = userService.updateProfile(user.id, request)

    @Operation(summary = "사용자 조회")
    @GetMapping("/{userId}")
    fun getUser(@PathVariable userId: Long): UserResponse = userService.getUser(userId)
}
