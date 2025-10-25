package com.cowork.user.dto

import jakarta.validation.constraints.Size

data class UpdateProfileRequest(
    @field:Size(max = 50)
    val nickname: String? = null,
    @field:Size(max = 500)
    val description: String? = null,
    @field:Size(max = 255)
    val specialty: String? = null,
)
