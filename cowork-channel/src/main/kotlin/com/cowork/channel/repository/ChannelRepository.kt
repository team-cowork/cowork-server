package com.cowork.channel.repository

import com.cowork.channel.domain.Channel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChannelRepository : JpaRepository<Channel, Long> {

    fun findAllByTeamIdOrderByIdAsc(teamId: Long): List<Channel>

    fun findAllByTeamIdAndCreatedByOrderByIdAsc(teamId: Long, createdBy: Long): List<Channel>

    fun findAllByCreatedBy(createdBy: Long): List<Channel>

    @Query("SELECT c.id FROM Channel c WHERE c.teamId = :teamId AND c.createdBy <> :createdBy ORDER BY c.id ASC")
    fun findIdsByTeamIdAndCreatedByNot(@Param("teamId") teamId: Long, @Param("createdBy") createdBy: Long): List<Long>
}
