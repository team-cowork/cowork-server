package com.cowork.channel.repository

import com.cowork.channel.domain.Channel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChannelRepository : JpaRepository<Channel, Long> {

    fun findAllByTeamIdOrderByPositionAscIdAsc(teamId: Long): List<Channel>

    fun findAllByProjectIdOrderByIdAsc(projectId: Long): List<Channel>

    fun findAllByTeamIdAndCreatedByOrderByIdAsc(teamId: Long, createdBy: Long): List<Channel>

    fun findAllByCreatedBy(createdBy: Long): List<Channel>

    @Query("SELECT COALESCE(MAX(c.position), -1) FROM Channel c WHERE c.teamId = :teamId")
    fun findMaxPositionByTeamId(@Param("teamId") teamId: Long): Int

    @Query("SELECT c.id FROM Channel c WHERE c.teamId = :teamId")
    fun findAllIdsByTeamId(@Param("teamId") teamId: Long): List<Long>

    @Query("SELECT c.id FROM Channel c WHERE c.teamId = :teamId AND c.createdBy <> :createdBy ORDER BY c.id ASC")
    fun findIdsByTeamIdAndCreatedByNot(@Param("teamId") teamId: Long, @Param("createdBy") createdBy: Long): List<Long>

    @Query("SELECT c FROM Channel c WHERE c.teamId = :teamId AND LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY c.position ASC, c.id ASC")
    fun searchByTeamIdAndName(@Param("teamId") teamId: Long, @Param("q") q: String): List<Channel>
}
