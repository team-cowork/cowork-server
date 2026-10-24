package com.cowork.channel.domain.sharedAccount.service.support

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.SharedAccount
import com.cowork.channel.domain.sharedAccount.presentation.data.response.SharedAccountResponse
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.CredentialEncryptionService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class SharedAccountLookupSupport(
    private val sharedAccountRepository: SharedAccountRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
    private val credentialEncryptionService: CredentialEncryptionService,
) {

    fun findAccountOrThrow(accountId: Long, channelId: Long): SharedAccount =
        sharedAccountRepository.findByIdAndChannelId(accountId, channelId)
            ?: throw ExpectedException("공유 계정을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)

    fun requireAccountEditor(account: SharedAccount, channel: Channel, userId: Long) {
        teamPermissionService.requireChannelAccess(channel, userId)
        if (account.createdBy != userId) {
            teamPermissionService.requireChannelPermission(channel, userId, "channel_manage")
        }
    }

    fun toResponse(account: SharedAccount, listOnly: Boolean = false): SharedAccountResponse {
        val maskedCredential = when {
            account.credential == null -> null

            listOnly -> "••••"

            else -> runCatching { credentialEncryptionService.decrypt(account.credential!!) }
                .getOrNull()
                ?.let { credentialEncryptionService.mask(it) }
        }
        return SharedAccountResponse.of(account, maskedCredential)
    }
}
