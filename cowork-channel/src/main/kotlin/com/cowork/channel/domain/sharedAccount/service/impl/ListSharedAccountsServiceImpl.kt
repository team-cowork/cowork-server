package com.cowork.channel.domain.sharedAccount.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.sharedAccount.presentation.data.response.SharedAccountResponse
import com.cowork.channel.domain.sharedAccount.repository.SharedAccountRepository
import com.cowork.channel.domain.sharedAccount.service.ListSharedAccountsService
import com.cowork.channel.domain.sharedAccount.service.SharedAccountAccessGuard
import com.cowork.channel.domain.sharedAccount.service.support.SharedAccountLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ListSharedAccountsServiceImpl(
    private val sharedAccountRepository: SharedAccountRepository,
    private val channelAccessGuard: ChannelAccessGuard,
    private val teamPermissionService: TeamPermissionService,
    private val sharedAccountAccessGuard: SharedAccountAccessGuard,
    private val lookupSupport: SharedAccountLookupSupport,
) : ListSharedAccountsService {

    override fun listAccounts(userId: Long, channelId: Long): List<SharedAccountResponse> {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        sharedAccountAccessGuard.requireAccountShareChannel(channel)
        teamPermissionService.requireTeamMember(channelAccessGuard.requireTeamChannel(channel), userId)
        return sharedAccountRepository.findAllByChannelIdOrderByCreatedAtAscIdAsc(channelId)
            .map { lookupSupport.toResponse(it, listOnly = true) }
    }
}
