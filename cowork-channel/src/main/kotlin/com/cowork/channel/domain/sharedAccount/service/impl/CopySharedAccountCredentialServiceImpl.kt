package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.AccountCredentialCopy
import com.cowork.channel.domain.sharedAccount.repository.AccountCredentialCopyRepository
import com.cowork.channel.domain.sharedAccount.service.CopySharedAccountCredentialService
import com.cowork.channel.domain.sharedAccount.service.CredentialEncryptionService
import com.cowork.channel.domain.sharedAccount.service.SharedAccountAccessGuard
import com.cowork.channel.domain.sharedAccount.service.support.SharedAccountLookupSupport
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class CopySharedAccountCredentialServiceImpl(
    private val accountCredentialCopyRepository: AccountCredentialCopyRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
    private val credentialEncryptionService: CredentialEncryptionService,
    private val sharedAccountAccessGuard: SharedAccountAccessGuard,
    private val lookupSupport: SharedAccountLookupSupport,
) : CopySharedAccountCredentialService {

    override fun copyCredential(userId: Long, channelId: Long, accountId: Long): String {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        sharedAccountAccessGuard.requireAccountShareChannel(channel)
        teamPermissionService.requireTeamMember(channelAccessGuard.requireTeamChannel(channel), userId)
        val account = lookupSupport.findAccountOrThrow(accountId, channelId)

        if (account.credential == null) {
            throw ExpectedException("저장된 credential이 없습니다.", HttpStatus.NOT_FOUND)
        }

        accountCredentialCopyRepository.save(
            AccountCredentialCopy(accountId = accountId, userId = userId),
        )

        return credentialEncryptionService.decrypt(account.credential!!)
    }
}
