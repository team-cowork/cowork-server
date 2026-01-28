package com.cowork.channel.account.repository

import com.cowork.channel.account.domain.CredentialCopyHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

interface CredentialCopyHistoryRepository : JpaRepository<CredentialCopyHistory, Long> {

    fun findTop20ByAccountIdOrderByIdDesc(accountId: Long): List<CredentialCopyHistory>

    @Modifying
    @Query("DELETE FROM CredentialCopyHistory h WHERE h.accountId IN :accountIds")
    fun deleteAllByAccountIdIn(accountIds: Collection<Long>)

    @Modifying
    @Query("DELETE FROM CredentialCopyHistory h WHERE h.copiedAt < :threshold")
    fun deleteAllCopiedBefore(threshold: LocalDateTime): Int
}
