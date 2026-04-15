package com.cowork.user.controller

import com.cowork.user.dto.*
import com.cowork.user.service.UserService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/users")
class UserController(
    private val userService: UserService,
) {

    @GetMapping("/me")
    fun getMyProfile(
        @RequestHeader("X-User-Id") userId: Long,
    ): ResponseEntity<MyProfileResponse> =
        ResponseEntity.ok(userService.getMyProfile(userId))

    @PatchMapping("/me")
    fun updateMyProfile(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: UpdateMyProfileRequest,
    ): ResponseEntity<MyProfileResponse> =
        ResponseEntity.ok(userService.updateMyProfile(userId, request))

    @PostMapping("/me/profile-image/presigned")
    fun generatePresignedUrl(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: PresignedUrlRequest,
    ): ResponseEntity<PresignedUrlResponse> =
        ResponseEntity.ok(userService.generatePresignedUrl(userId, request.contentType))

    @PostMapping("/me/profile-image/confirm")
    fun confirmUpload(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: ConfirmUploadRequest,
    ): ResponseEntity<Void> {
        userService.confirmUpload(userId, request.objectKey)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/me/profile-image")
    fun deleteProfileImage(
        @RequestHeader("X-User-Id") userId: Long,
    ): ResponseEntity<Void> {
        userService.deleteProfileImage(userId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{userId}")
    fun getUserProfile(
        @PathVariable userId: Long,
    ): ResponseEntity<UserProfileResponse> =
        ResponseEntity.ok(userService.getUserProfile(userId))

    @GetMapping("/search")
    fun searchUsers(
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) nickname: String?,
        @RequestParam(required = false) major: String?,
        @RequestParam(required = false) studentRole: String?,
        @RequestParam(name = "stRole", required = false) legacyStudentRole: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) role: String?,
        @PageableDefault(size = 20, sort = ["id"]) pageable: Pageable,
    ): ResponseEntity<Page<UserProfileResponse>> =
        ResponseEntity.ok(
            userService.searchUsers(
                name = name,
                nickname = nickname,
                major = major,
                studentRole = studentRole ?: legacyStudentRole,
                status = status,
                role = role,
                pageable = pageable,
            ),
        )
}
