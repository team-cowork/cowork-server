package com.cowork.user.dto

import com.cowork.user.domain.Major
import com.cowork.user.domain.Role
import com.cowork.user.domain.Sex
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "사용자 생성/갱신 요청 (authorization 서비스 내부 호출용)")
data class UpsertUserRequest(
    @Schema(description = "실명") val name: String,
    @Schema(description = "이메일") val email: String,
    @Schema(description = "성별") val sex: Sex,
    @Schema(description = "학년") val grade: Byte?,
    @Schema(description = "반") val `class`: Byte?,
    @Schema(description = "번호") val classNum: Byte?,
    @Schema(description = "전공") val major: Major,
    @Schema(description = "권한") val role: Role,
    @Schema(description = "GitHub 아이디") val githubId: String?,
)
