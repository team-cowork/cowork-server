package com.cowork.channel.domain.sharedAccount.service

import com.cowork.channel.domain.sharedAccount.entity.SharedAccount

interface HandleOAuthCallbackService {
    fun handleCallback(providerName: String, code: String, state: String): SharedAccount
}
