package com.cowork.channel.repository

import com.cowork.channel.domain.AccountCredentialCopy
import org.springframework.data.jpa.repository.JpaRepository

interface AccountCredentialCopyRepository : JpaRepository<AccountCredentialCopy, Long>
