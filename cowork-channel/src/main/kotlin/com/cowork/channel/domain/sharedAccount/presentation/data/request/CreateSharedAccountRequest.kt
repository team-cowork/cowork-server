package com.cowork.channel.domain.sharedAccount.presentation.data.request

import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import io.swagger.v3.oas.annotations.media.Schema

data class CreateSharedAccountRequest(
    @param:Schema(description = "서비스 종류 (OAuth 미지원 서비스는 CUSTOM 사용)", example = "GITHUB")
    val provider: AccountProvider,

    @param:Schema(description = "CUSTOM 서비스일 때 사용자 정의 서비스 이름", example = "사내 Jenkins")
    val providerLabel: String?,

    @param:Schema(description = "로그인 식별자 (이메일 또는 username)", example = "team@company.com")
    val accountIdentifier: String?,

    @param:Schema(description = "비밀번호 또는 API 키 (서버에서 암호화 저장)", example = "MyP@ssw0rd!")
    val credential: String?,
)
