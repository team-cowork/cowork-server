package com.cowork.channel.account.repository

import com.cowork.channel.account.domain.SharedAccount
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface SharedAccountRepository : JpaRepository<SharedAccount, Long> {

    fun findAllByChannelIdOrderByIdAsc(channelId: Long): List<SharedAccount>

    fun findByIdAndChannelId(id: Long, channelId: Long): SharedAccount?

    @Query("SELECT a.id FROM SharedAccount a WHERE a.channelId = :channelId")
    fun findIdsByChannelId(channelId: Long): List<Long>
}
