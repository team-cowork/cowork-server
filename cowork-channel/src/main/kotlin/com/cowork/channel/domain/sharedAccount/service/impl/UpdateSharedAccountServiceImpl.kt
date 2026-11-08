package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.sharedAccount.presentation.data.request.UpdateSharedAccountRequest
import com.cowork.channel.domain.sharedAccount.presentation.data.response.SharedAccountResponse
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.CredentialEncryptionService
import com.cowork.channel.domain.sharedAccount.service.SharedAccountAccessGuard
import com.cowork.channel.domain.sharedAccount.service.UpdateSharedAccountService
import com.cowork.channel.domain.sharedAccount.service.support.SharedAccountLookupSupport
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class UpdateSharedAccountServiceImpl(
    private val sharedAccountRepository: SharedAccountRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val credentialEncryptionService: CredentialEncryptionService,
    private val sharedAccountAccessGuard: SharedAccountAccessGuard,
    private val lookupSupport: SharedAccountLookupSupport,
) : UpdateSharedAccountService {

    override fun updateAccount(
        userId: Long,
        channelId: Long,
        accountId: Long,
        request: UpdateSharedAccountRequest,
    ): SharedAccountResponse {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        sharedAccountAccessGuard.requireAccountShareChannel(channel)
        val account = lookupSupport.findAccountOrThrow(accountId, channelId)
        lookupSupport.requireAccountEditor(account, channel, userId)

        val encryptedCredential = request.credential?.let { credentialEncryptionService.encrypt(it) }
        account.update(request.accountIdentifier, encryptedCredential, request.providerLabel)
        // 변경된 식별자의 unique 위반을 커밋 시점 500이 아니라 409로 응답하려고 즉시 flush한다.
        try {
            sharedAccountRepository.flush()
        } catch (e: DataIntegrityViolationException) {
            throw ExpectedException("이미 채널에 등록된 공유 계정입니다.", HttpStatus.CONFLICT)
        }
        return lookupSupport.toResponse(account)
    }
}
