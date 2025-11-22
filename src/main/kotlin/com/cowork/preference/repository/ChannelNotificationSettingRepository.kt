package com.cowork.preference.repository

import com.cowork.preference.domain.ChannelNotificationSetting
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface ChannelNotificationSettingRepository : JpaRepository<ChannelNotificationSetting, Long> {

    fun findByUserIdAndChannelId(userId: Long, channelId: Long): ChannelNotificationSetting?

    @Query(
        """
        SELECT s.userId FROM ChannelNotificationSetting s
        WHERE s.channelId = :channelId AND s.enabled = false AND s.userId IN :userIds
        """,
    )
    fun findMutedUserIds(channelId: Long, userIds: Collection<Long>): List<Long>

    @Modifying
    @Query("DELETE FROM ChannelNotificationSetting s WHERE s.channelId = :channelId")
    fun deleteAllByChannelId(channelId: Long)
}
