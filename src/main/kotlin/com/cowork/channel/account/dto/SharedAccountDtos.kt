package com.cowork.channel.account.dto

import com.cowork.channel.account.domain.AccountProvider
import com.cowork.channel.account.domain.CredentialCopyHistory
import com.cowork.channel.account.domain.SharedAccount
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateSharedAccountRequest(
    @field:NotNull
    val provider: AccountProvider,
    @field:Size(max = 100)
    val providerLabel: String? = null,
    @field:Size(max = 255)
    val accountIdentifier: String? = null,
    @field:Size(max = 2000)
    val credential: String? = null,
)

data class UpdateSharedAccountRequest(
    @field:Size(max = 100)
    val providerLabel: String? = null,
    @field:Size(max = 255)
    val accountIdentifier: String? = null,
    @field:Size(max = 2000)
    val credential: String? = null,
)

data class SharedAccountResponse(
    val id: Long,
    val channelId: Long,
    val provider: AccountProvider,
    val providerName: String?,
    val loginUrl: String?,
    val accountIdentifier: String?,
    val hasCredential: Boolean,
    val connectedViaOAuth: Boolean,
    val createdBy: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(account: SharedAccount) = SharedAccountResponse(
            id = account.id,
            channelId = account.channelId,
            provider = account.provider,
            providerName = account.providerLabel ?: account.provider.displayName,
            loginUrl = account.provider.loginUrl,
            accountIdentifier = account.accountIdentifier,
            hasCredential = account.hasCredential,
            connectedViaOAuth = account.connectedViaOAuth,
            createdBy = account.createdBy,
            createdAt = account.createdAt,
        )
    }
}

data class SharedAccountDetailResponse(
    val account: SharedAccountResponse,
    val recentCopies: List<CredentialCopyResponse>,
)

data class CredentialCopyResponse(
    val userId: Long,
    val copiedAt: LocalDateTime,
) {
    companion object {
        fun from(history: CredentialCopyHistory) = CredentialCopyResponse(history.userId, history.copiedAt)
    }
}

data class CredentialResponse(
    val credential: String,
)
