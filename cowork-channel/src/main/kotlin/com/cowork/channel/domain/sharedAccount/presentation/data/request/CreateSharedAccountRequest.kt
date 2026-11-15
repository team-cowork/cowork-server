package com.cowork.channel.domain.sharedAccount.presentation.data.request

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

data class CreateSharedAccountRequest(
    @param:Schema(description = "서비스 종류 (OAuth 미지원 서비스는 CUSTOM 사용)", example = "GITHUB")
    val provider: AccountProvider,

    @param:Schema(description = "CUSTOM 서비스일 때 사용자 정의 서비스 이름", example = "사내 Jenkins")
    @field:Size(max = 100)
    val providerLabel: String?,

    @param:Schema(description = "로그인 식별자 (이메일 또는 username)", example = "team@company.com")
    @field:Size(max = 255)
    val accountIdentifier: String?,

    // 16,000자: 3바이트 UTF-8 최악 기준 AES-GCM+Base64+IV 후에도 TEXT(65,535바이트)에 들어간다 (최대 16,373자).
    @param:Schema(description = "비밀번호 또는 API 키 (서버에서 암호화 저장, 최대 16,000자)", example = "MyP@ssw0rd!")
    @field:Size(max = 16000)
    val credential: String?,
)
