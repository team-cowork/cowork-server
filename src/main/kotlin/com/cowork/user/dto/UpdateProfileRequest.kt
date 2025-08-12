package com.cowork.user.dto


data class UpdateProfileRequest(
    val nickname: String? = null,
    val description: String? = null,
    val specialty: String? = null,
)
