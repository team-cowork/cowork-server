package com.cowork.channel.repository

import com.cowork.channel.domain.SharedAccount
import org.springframework.data.jpa.repository.JpaRepository

interface SharedAccountRepository : JpaRepository<SharedAccount, Long> {

    fun findAllByChannelIdOrderByCreatedAtAsc(channelId: Long): List<SharedAccount>

    fun findByIdAndChannelId(id: Long, channelId: Long): SharedAccount?
}
