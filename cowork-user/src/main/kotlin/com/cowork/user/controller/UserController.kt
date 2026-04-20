package com.cowork.user.controller

import com.cowork.user.dto.*
import com.cowork.user.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "User", description = "사용자 프로필 및 계정 관리 API")
@RestController
@RequestMapping("/users")
class UserController(
    private val userService: UserService,
) {

    @Operation(summary = "내 프로필 조회", description = "현재 인증된 사용자의 프로필 정보를 반환합니다.")
    @GetMapping("/me")
    fun getMyProfile(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
    ): ResponseEntity<MyProfileResponse> =
        ResponseEntity.ok(userService.getMyProfile(userId))

    @Operation(summary = "내 프로필 수정", description = "닉네임, 소개, 역할 등 프로필 정보를 수정합니다.")
    @PatchMapping("/me")
    fun updateMyProfile(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: UpdateMyProfileRequest,
    ): ResponseEntity<MyProfileResponse> =
        ResponseEntity.ok(userService.updateMyProfile(userId, request))

    @Operation(summary = "프로필 이미지 업로드 URL 발급", description = "S3 Presigned PUT URL을 발급합니다. 클라이언트가 직접 S3에 업로드 후 confirm을 호출해야 합니다.")
    @PostMapping("/me/profile-image/presigned")
    fun generatePresignedUrl(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: PresignedUrlRequest,
    ): ResponseEntity<PresignedUrlResponse> =
        ResponseEntity.ok(userService.generatePresignedUrl(userId, request.contentType))

    @Operation(summary = "프로필 이미지 업로드 확인", description = "S3 업로드 완료 후 objectKey를 전달하여 프로필 이미지를 확정합니다.")
    @PostMapping("/me/profile-image/confirm")
    fun confirmUpload(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: ConfirmUploadRequest,
    ): ResponseEntity<Void> {
        userService.confirmUpload(userId, request.objectKey)
        return ResponseEntity.ok().build()
    }

    @Operation(summary = "프로필 이미지 삭제")
    @DeleteMapping("/me/profile-image")
    fun deleteProfileImage(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
    ): ResponseEntity<Void> {
        userService.deleteProfileImage(userId)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "특정 사용자 프로필 조회")
    @GetMapping("/{userId}")
    fun getUserProfile(
        @PathVariable userId: Long,
    ): ResponseEntity<UserProfileResponse> =
        ResponseEntity.ok(userService.getUserProfile(userId))

    @Operation(summary = "사용자 upsert (내부용)", description = "OAuth 로그인 시 authorization 서비스가 호출합니다. 사용자가 없으면 생성, 있으면 갱신합니다.")
    @PutMapping("/{userId}")
    fun upsertUser(
        @PathVariable userId: Long,
        @RequestBody request: UpsertUserRequest,
    ): ResponseEntity<UserProfileResponse> =
        ResponseEntity.ok(userService.upsertUser(userId, request))

    @Operation(summary = "사용자 검색", description = "이름, 닉네임, 전공, 역할, 상태 등의 조건으로 사용자를 검색합니다. 페이지네이션 지원.")
    @GetMapping("/search")
    fun searchUsers(
        @Parameter(description = "이름 검색") @RequestParam(required = false) name: String?,
        @Parameter(description = "닉네임 검색") @RequestParam(required = false) nickname: String?,
        @Parameter(description = "전공 필터") @RequestParam(required = false) major: String?,
        @Parameter(description = "학생 역할 필터 (studentRole)") @RequestParam(required = false) studentRole: String?,
        @Parameter(description = "학생 역할 필터 (레거시 stRole)", deprecated = true) @RequestParam(name = "stRole", required = false) legacyStudentRole: String?,
        @Parameter(description = "상태 필터") @RequestParam(required = false) status: String?,
        @Parameter(description = "권한 필터") @RequestParam(required = false) role: String?,
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
