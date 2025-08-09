package com.cowork.auth.dto


data class TokenRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
)
