package com.cowork.user.dto

import com.cowork.user.domain.Major
import com.cowork.user.domain.Role
import com.cowork.user.domain.Sex

data class UpsertUserRequest(
    val userId: Long,
    val name: String,
    val email: String,
    val sex: Sex,
    val grade: Byte?,
    val `class`: Byte?,
    val classNum: Byte?,
    val major: Major,
    val role: Role,
    val githubId: String?,
)
