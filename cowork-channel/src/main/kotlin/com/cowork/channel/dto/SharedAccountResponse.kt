package com.cowork.channel.dto

import com.cowork.channel.domain.SharedAccount
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class SharedAccountResponse(
    @Schema(description = "공유 계정 ID", example = "1")
    val id: Long,

    @Schema(description = "채널 ID", example = "10")
    val channelId: Long,

    @Schema(description = "서비스 종류 enum 값", example = "GITHUB")
    val provider: String,

    @Schema(description = "CUSTOM 서비스일 때 사용자 정의 이름", example = "사내 Jenkins")
    val providerLabel: String?,

    @Schema(description = "화면에 표시할 서비스 이름", example = "GitHub")
    val displayName: String?,

    @Schema(description = "해당 서비스 로그인 페이지 URL", example = "https://github.com/login")
    val loginUrl: String?,

    @Schema(description = "로그인 식별자 (이메일 또는 username)", example = "team@company.com")
    val accountIdentifier: String?,

    @Schema(description = "마스킹된 credential (마지막 4자리만 노출)", example = "••••w0rd!")
    val maskedCredential: String?,

    @Schema(description = "OAuth 연동으로 등록된 계정 여부")
    val connectedViaOAuth: Boolean,

    @Schema(description = "등록한 사용자 ID", example = "42")
    val createdBy: Long,

    @Schema(description = "등록 일시")
    val createdAt: LocalDateTime,

    @Schema(description = "최종 수정 일시")
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(account: SharedAccount, maskedCredential: String?) = SharedAccountResponse(
            id = account.id,
            channelId = account.channelId,
            provider = account.provider.name,
            providerLabel = account.providerLabel,
            displayName = account.provider.displayName ?: account.providerLabel,
            loginUrl = account.provider.loginUrl,
            accountIdentifier = account.accountIdentifier,
            maskedCredential = maskedCredential,
            connectedViaOAuth = account.connectedViaOAuth,
            createdBy = account.createdBy,
            createdAt = account.createdAt,
            updatedAt = account.updatedAt,
        )
    }
}
