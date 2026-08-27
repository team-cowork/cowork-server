package com.cowork.project.domain.channel.repository

import com.cowork.project.domain.channel.entity.ChannelProjection
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChannelProjectionRepository : JpaRepository<ChannelProjection, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT channel FROM ChannelProjection channel WHERE channel.channelId = :channelId")
    fun findByIdForUpdate(@Param("channelId") channelId: Long): ChannelProjection?
}
