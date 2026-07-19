package com.cowork.channel.domain.sharedAccount.repository

import com.cowork.channel.domain.sharedAccount.entity.AccountCredentialCopy
import org.springframework.data.jpa.repository.JpaRepository

interface AccountCredentialCopyRepository : JpaRepository<AccountCredentialCopy, Long>
