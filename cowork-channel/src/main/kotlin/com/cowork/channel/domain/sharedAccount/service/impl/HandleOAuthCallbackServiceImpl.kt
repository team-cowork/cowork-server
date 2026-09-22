package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.entity.AccountProvider
import com.cowork.channel.domain.sharedAccount.entity.SharedAccount
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.HandleOAuthCallbackService
import com.cowork.channel.domain.sharedAccount.service.support.OAuthIdentityResolver
import com.cowork.channel.domain.sharedAccount.service.support.OAuthStateSupport
import com.cowork.channel.global.config.OAuthProperties
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import team.themoment.sdk.exception.ExpectedException

/**
 * OAuth 콜백을 세 단계로 나누어 처리한다.
 *
 * 1. 권한 검증 — 짧은 read transaction
 * 2. 제공자 호출 — transaction 없음
 * 3. 계정 저장 — 짧은 write transaction
 *
 * 외부 제공자 응답을 기다리는 동안 database connection을 점유하지 않도록 2단계를
 * transaction 밖에 둔다. 자기 호출은 Spring proxy를 거치지 않아 `@Transactional`이
 * 적용되지 않으므로, 경계는 [TransactionTemplate]으로 명시한다.
 */
@Service
class HandleOAuthCallbackServiceImpl(
    private val oAuthProperties: OAuthProperties,
    private val sharedAccountRepository: SharedAccountRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
    private val oAuthStateSupport: OAuthStateSupport,
    private val oAuthIdentityResolver: OAuthIdentityResolver,
    transactionManager: PlatformTransactionManager,
) : HandleOAuthCallbackService {

    private val readTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writeTransaction = TransactionTemplate(transactionManager)

    override fun handleCallback(providerName: String, code: String, state: String): SharedAccount {
        val provider = runCatching { AccountProvider.valueOf(providerName.uppercase()) }.getOrElse {
            throw ExpectedException("지원하지 않는 OAuth provider입니다.", HttpStatus.BAD_REQUEST)
        }

        val (channelId, userId) = oAuthStateSupport.verifyState(state, provider)
        readTransaction.executeWithoutResult { authorize(channelId, userId) }

        val config = oAuthStateSupport.providerConfigOf(provider)
        val callbackUrl = oAuthProperties.callbackUrl(provider.name)
        val identifier = oAuthIdentityResolver.resolveIdentifier(provider, config, code, callbackUrl)

        return try {
            requireNotNull(
                writeTransaction.execute { persist(channelId, userId, provider, identifier) },
            ) { "Shared account persistence returned no result." }
        } catch (exception: DataIntegrityViolationException) {
            // 같은 계정으로 콜백이 동시에 도착하면 uq_tb_channel_accounts_channel_provider_identifier
            // 충돌이 날 수 있다. 재조회로 흡수해 멱등하게 만든다.
            readTransaction.execute {
                sharedAccountRepository.findByChannelIdAndProviderAndAccountIdentifier(channelId, provider, identifier)
            } ?: throw exception
        }
    }

    /** 채널 접근 권한을 검증한다. 외부 호출 전에 완료되어 connection을 즉시 반납한다. */
    private fun authorize(channelId: Long, userId: Long) {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        teamPermissionService.requireTeamMember(channelAccessGuard.requireTeamChannel(channel), userId)
    }

    /** 이미 연결된 계정이면 그대로 반환하고, 없으면 새로 저장한다. */
    private fun persist(channelId: Long, userId: Long, provider: AccountProvider, identifier: String): SharedAccount {
        sharedAccountRepository.findByChannelIdAndProviderAndAccountIdentifier(channelId, provider, identifier)
            ?.let { return it }

        return sharedAccountRepository.save(
            SharedAccount(
                channelId = channelId,
                provider = provider,
                providerLabel = null,
                accountIdentifier = identifier,
                connectedViaOAuth = true,
                credential = null,
                createdBy = userId,
            ),
        )
    }
}
