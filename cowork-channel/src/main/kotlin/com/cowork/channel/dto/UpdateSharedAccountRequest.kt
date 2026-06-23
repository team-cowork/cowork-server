package com.cowork.channel.dto

import io.swagger.v3.oas.annotations.media.Schema

data class UpdateSharedAccountRequest(
    @param:Schema(description = "로그인 식별자 (이메일 또는 username)", example = "team@company.com")
    val accountIdentifier: String?,

    @param:Schema(description = "변경할 비밀번호 또는 API 키", example = "NewP@ssw0rd!")
    val credential: String?,

    @param:Schema(description = "CUSTOM 서비스 이름 변경", example = "사내 Jenkins (신규)")
    val providerLabel: String?,
)
