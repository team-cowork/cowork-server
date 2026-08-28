package com.cowork.channel.domain.channel.repository

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChannelRepository : JpaRepository<Channel, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Channel c WHERE c.id = :channelId")
    fun findByIdForUpdate(@Param("channelId") channelId: Long): Channel?

    fun findAllByTeamIdOrderByPositionAscIdAsc(teamId: Long): List<Channel>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Channel c WHERE c.teamId = :teamId ORDER BY c.id")
    fun findAllByTeamIdForUpdateOrderByIdAsc(@Param("teamId") teamId: Long): List<Channel>

    fun findByDmKey(dmKey: String): Channel?

    fun existsByIdAndType(id: Long, type: ChannelType): Boolean

    fun findAllByProjectIdOrderByIdAsc(projectId: Long): List<Channel>

    @Query("SELECT COALESCE(MAX(c.position), -1) FROM Channel c WHERE c.teamId = :teamId")
    fun findMaxPositionByTeamId(@Param("teamId") teamId: Long): Int

    @Query("SELECT c.id FROM Channel c WHERE c.teamId = :teamId")
    fun findAllIdsByTeamId(@Param("teamId") teamId: Long): List<Long>

    @Query(
        "SELECT c FROM Channel c WHERE c.teamId = :teamId AND LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY c.position ASC, c.id ASC",
    )
    fun searchByTeamIdAndName(@Param("teamId") teamId: Long, @Param("q") q: String): List<Channel>
}
