package com.cowork.channel.domain.sharedAccount.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

data class UpdateSharedAccountRequest(
    @param:Schema(description = "로그인 식별자 (이메일 또는 username)", example = "team@company.com")
    @field:Size(max = 255)
    val accountIdentifier: String?,

    // 16,000자: 3바이트 UTF-8 최악 기준 AES-GCM+Base64+IV 후에도 TEXT(65,535바이트)에 들어간다 (최대 16,374자).
    @param:Schema(description = "변경할 비밀번호 또는 API 키 (최대 16,000자)", example = "NewP@ssw0rd!")
    @field:Size(max = 16000)
    val credential: String?,

    @param:Schema(description = "CUSTOM 서비스 이름 변경", example = "사내 Jenkins (신규)")
    @field:Size(max = 100)
    val providerLabel: String?,
)
