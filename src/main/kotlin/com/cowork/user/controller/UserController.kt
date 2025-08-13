package com.cowork.user.controller

import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.global.storage.PresignedUploadRequest
import com.cowork.global.storage.PresignedUploadResponse
import com.cowork.user.dto.ConfirmProfileImageRequest
import com.cowork.user.dto.UpdateProfileRequest
import com.cowork.user.dto.UserResponse
import com.cowork.user.dto.UserSummaryResponse
import com.cowork.user.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
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

    @Operation(summary = "프로필 이미지 업로드 URL 발급")
    @PostMapping("/me/profile-image/presigned")
    fun createProfileImageUploadUrl(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: PresignedUploadRequest,
    ): PresignedUploadResponse = userService.createProfileImageUploadUrl(user.id, request)

    @Operation(summary = "프로필 이미지 업로드 확정")
    @PostMapping("/me/profile-image/confirm")
    fun confirmProfileImage(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: ConfirmProfileImageRequest,
    ): UserResponse = userService.confirmProfileImage(user.id, request.objectKey)
    @Operation(summary = "사용자 검색")
    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) major: String?,
        @RequestParam(required = false) grade: Int?,
        @PageableDefault(size = 20) pageable: Pageable,
    ): Page<UserSummaryResponse> = userService.search(keyword, major, grade, pageable)

    @Operation(summary = "사용자 조회")
    @GetMapping("/{userId}")
    fun getUser(@PathVariable userId: Long): UserResponse = userService.getUser(userId)
}
