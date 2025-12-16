package com.cowork.notification.repository

import com.cowork.notification.domain.DeviceToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface DeviceTokenRepository : JpaRepository<DeviceToken, Long> {

    fun findByToken(token: String): DeviceToken?

    fun findAllByUserIdIn(userIds: Collection<Long>): List<DeviceToken>

    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.token IN :tokens")
    fun deleteAllByTokenIn(tokens: Collection<String>)
}
