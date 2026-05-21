package com.cowork.channel.service

import com.cowork.channel.domain.AccountCredentialCopy
import com.cowork.channel.domain.AccountProvider
import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.domain.SharedAccount
import com.cowork.channel.dto.CreateSharedAccountRequest
import com.cowork.channel.dto.SharedAccountResponse
import com.cowork.channel.dto.UpdateSharedAccountRequest
import com.cowork.channel.repository.AccountCredentialCopyRepository
import com.cowork.channel.repository.SharedAccountRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional(readOnly = true)
class SharedAccountService(
    private val sharedAccountRepository: SharedAccountRepository,
    private val accountCredentialCopyRepository: AccountCredentialCopyRepository,
    private val channelService: ChannelService,
    private val teamPermissionService: TeamPermissionService,
    private val credentialEncryptionService: CredentialEncryptionService,
) {

    private fun findAccountOrThrow(accountId: Long, channelId: Long): SharedAccount =
        sharedAccountRepository.findByIdAndChannelId(accountId, channelId)
            ?: throw ExpectedException("공유 계정을 찾을 수 없습니다. id=$accountId", HttpStatus.NOT_FOUND)

    private fun requireAccountShareChannel(channel: Channel) {
        if (channel.viewType != ChannelViewType.ACCOUNT_SHARE) {
            throw ExpectedException("ACCOUNT_SHARE 채널에서만 계정 공유 기능을 사용할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
    }

    private fun requireAccountEditor(account: SharedAccount, channel: Channel, userId: Long) {
        if (account.createdBy != userId
            && channel.createdBy != userId
            && !teamPermissionService.isTeamOwnerOrAdmin(channel.teamId, userId)
        ) {
            throw ExpectedException("공유 계정을 수정하거나 삭제할 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    fun listAccounts(userId: Long, channelId: Long): List<SharedAccountResponse> {
        val channel = channelService.findChannelOrThrow(channelId)
        requireAccountShareChannel(channel)
        teamPermissionService.requireTeamMember(channel.teamId, userId)
        return sharedAccountRepository.findAllByChannelIdOrderByCreatedAtAsc(channelId)
            .map { toResponse(it) }
    }

    fun getAccount(userId: Long, channelId: Long, accountId: Long): SharedAccountResponse {
        val channel = channelService.findChannelOrThrow(channelId)
        requireAccountShareChannel(channel)
        teamPermissionService.requireTeamMember(channel.teamId, userId)
        return toResponse(findAccountOrThrow(accountId, channelId))
    }

    @Transactional
    fun createAccount(userId: Long, channelId: Long, request: CreateSharedAccountRequest): SharedAccountResponse {
        val channel = channelService.findChannelOrThrow(channelId)
        requireAccountShareChannel(channel)
        teamPermissionService.requireTeamMember(channel.teamId, userId)

        if (request.provider == AccountProvider.CUSTOM && request.providerLabel.isNullOrBlank()) {
            throw ExpectedException("CUSTOM 서비스는 providerLabel이 필요합니다.", HttpStatus.BAD_REQUEST)
        }

        val encryptedCredential = request.credential?.let { credentialEncryptionService.encrypt(it) }

        val account = sharedAccountRepository.save(
            SharedAccount(
                channelId = channelId,
                provider = request.provider,
                providerLabel = request.providerLabel,
                accountIdentifier = request.accountIdentifier,
                credential = encryptedCredential,
                connectedViaOAuth = false,
                createdBy = userId,
            )
        )
        return toResponse(account)
    }

    @Transactional
    fun updateAccount(
        userId: Long,
        channelId: Long,
        accountId: Long,
        request: UpdateSharedAccountRequest,
    ): SharedAccountResponse {
        val channel = channelService.findChannelOrThrow(channelId)
        requireAccountShareChannel(channel)
        val account = findAccountOrThrow(accountId, channelId)
        requireAccountEditor(account, channel, userId)

        val encryptedCredential = request.credential?.let { credentialEncryptionService.encrypt(it) }
        account.update(request.accountIdentifier, encryptedCredential, request.providerLabel)
        return toResponse(account)
    }

    @Transactional
    fun deleteAccount(userId: Long, channelId: Long, accountId: Long) {
        val channel = channelService.findChannelOrThrow(channelId)
        requireAccountShareChannel(channel)
        val account = findAccountOrThrow(accountId, channelId)
        requireAccountEditor(account, channel, userId)
        sharedAccountRepository.delete(account)
    }

    @Transactional
    fun copyCredential(userId: Long, channelId: Long, accountId: Long): String {
        val channel = channelService.findChannelOrThrow(channelId)
        teamPermissionService.requireTeamMember(channel.teamId, userId)
        val account = findAccountOrThrow(accountId, channelId)

        if (account.credential == null) {
            throw ExpectedException("저장된 credential이 없습니다.", HttpStatus.NOT_FOUND)
        }

        accountCredentialCopyRepository.save(
            AccountCredentialCopy(accountId = accountId, userId = userId)
        )

        return credentialEncryptionService.decrypt(account.credential!!)
    }

    private fun toResponse(account: SharedAccount): SharedAccountResponse {
        val maskedCredential = account.credential
            ?.let { runCatching { credentialEncryptionService.decrypt(it) }.getOrNull() }
            ?.let { credentialEncryptionService.mask(it) }

        return SharedAccountResponse.of(account, maskedCredential)
    }
}
