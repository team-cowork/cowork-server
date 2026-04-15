package com.cowork.user.dto

data class UpdateMyProfileRequest(
    val nickname: String?,
    val description: String?,
    val roles: List<String>?,
)
