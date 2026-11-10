package com.cowork.channel.domain.channel.repository

import com.cowork.channel.domain.channel.entity.Channel
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ChannelRepository : JpaRepository<Channel, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Channel c WHERE c.id = :channelId")
    fun findByIdForUpdate(@Param("channelId") channelId: Long): Channel?

    @Query(
        "SELECT c FROM Channel c " +
            "WHERE c.teamId = :teamId " +
            "AND c.archivedAt IS NULL " +
            "ORDER BY c.position ASC, c.id ASC",
    )
    fun findActiveByTeamIdOrderByPositionAscIdAsc(@Param("teamId") teamId: Long): List<Channel>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Channel c WHERE c.teamId = :teamId ORDER BY c.id")
    fun findAllByTeamIdForUpdateOrderByIdAsc(@Param("teamId") teamId: Long): List<Channel>

    fun findByDmKey(dmKey: String): Channel?

    @Query(
        "SELECT c FROM Channel c " +
            "WHERE c.projectId = :projectId " +
            "AND c.teamId = :teamId " +
            "AND c.archivedAt IS NULL " +
            "ORDER BY c.id ASC",
    )
    fun findActiveByProjectIdAndTeamIdOrderByIdAsc(
        @Param("projectId") projectId: Long,
        @Param("teamId") teamId: Long,
    ): List<Channel>

    @Query(
        "SELECT c FROM Channel c " +
            "WHERE c.teamId = :teamId " +
            "AND c.archivedAt IS NULL " +
            "AND c.name LIKE CONCAT('%', :q, '%') ESCAPE '!' " +
            "ORDER BY c.position ASC, c.id ASC",
    )
    fun searchActiveByTeamIdAndName(@Param("teamId") teamId: Long, @Param("q") q: String): List<Channel>

    @Query(
        "SELECT c FROM Channel c " +
            "WHERE c.teamId = :teamId " +
            "AND c.archivedAt IS NOT NULL " +
            "AND (:channelId IS NULL OR c.id = :channelId) " +
            "AND (:projectId IS NULL OR c.projectId = :projectId) " +
            "AND (:q IS NULL OR c.name LIKE CONCAT('%', :q, '%') ESCAPE '!') " +
            "AND (:cursorAt IS NULL OR c.archivedAt < :cursorAt OR (c.archivedAt = :cursorAt AND c.id < :cursorId)) " +
            "ORDER BY c.archivedAt DESC, c.id DESC",
    )
    fun searchArchived(
        @Param("teamId") teamId: Long,
        @Param("channelId") channelId: Long?,
        @Param("projectId") projectId: Long?,
        @Param("q") q: String?,
        @Param("cursorAt") cursorAt: LocalDateTime?,
        @Param("cursorId") cursorId: Long?,
        limit: Limit,
    ): List<Channel>
}
