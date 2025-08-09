package com.cowork.auth.dto

import jakarta.validation.constraints.NotBlank

data class TokenRequest(
    @field:NotBlank
    val code: String,
    @field:NotBlank
    val codeVerifier: String,
    @field:NotBlank
    val redirectUri: String,
)
