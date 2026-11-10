package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.entity.SharedAccount
import com.cowork.channel.domain.sharedAccount.presentation.data.request.CreateSharedAccountRequest
import com.cowork.channel.domain.sharedAccount.presentation.data.response.SharedAccountResponse
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.CreateSharedAccountService
import com.cowork.channel.domain.sharedAccount.service.CredentialEncryptionService
import com.cowork.channel.domain.sharedAccount.service.SharedAccountAccessGuard
import com.cowork.channel.domain.sharedAccount.service.support.SharedAccountLookupSupport
import com.cowork.channel.domain.sharedAccount.service.support.toSharedAccountConflict
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class CreateSharedAccountServiceImpl(
    private val sharedAccountRepository: SharedAccountRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
    private val credentialEncryptionService: CredentialEncryptionService,
    private val sharedAccountAccessGuard: SharedAccountAccessGuard,
    private val lookupSupport: SharedAccountLookupSupport,
) : CreateSharedAccountService {

    override fun createAccount(
        userId: Long,
        channelId: Long,
        request: CreateSharedAccountRequest,
    ): SharedAccountResponse {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        sharedAccountAccessGuard.requireAccountShareChannel(channel)
        teamPermissionService.requireChannelAccess(channel, userId)
        channelAccessGuard.requireNotArchived(channel)

        if (request.provider == AccountProvider.CUSTOM && request.providerLabel.isNullOrBlank()) {
            throw ExpectedException("CUSTOM 서비스는 providerLabel이 필요합니다.", HttpStatus.BAD_REQUEST)
        }

        val encryptedCredential = request.credential?.let { credentialEncryptionService.encrypt(it) }

        // (channel_id, provider, account_identifier) unique 위반을 500이 아니라 409로 응답하려고 즉시 flush한다.
        val account = try {
            sharedAccountRepository.saveAndFlush(
                SharedAccount(
                    channelId = channelId,
                    provider = request.provider,
                    providerLabel = request.providerLabel,
                    accountIdentifier = request.accountIdentifier,
                    credential = encryptedCredential,
                    connectedViaOAuth = false,
                    createdBy = userId,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            throw e.toSharedAccountConflict()
        }
        return lookupSupport.toResponse(account)
    }
}
