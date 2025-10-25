package com.cowork.channel.repository

import com.cowork.channel.domain.Channel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface ChannelRepository : JpaRepository<Channel, Long> {

    fun findAllByTeamIdOrderByPositionAscIdAsc(teamId: Long): List<Channel>

    fun findAllByProjectIdOrderByPositionAscIdAsc(projectId: Long): List<Channel>

    @Query("SELECT COALESCE(MAX(c.position), -1) FROM Channel c WHERE c.teamId = :teamId")
    fun findMaxPosition(teamId: Long): Int

    @Query("SELECT c.id FROM Channel c WHERE c.teamId = :teamId")
    fun findIdsByTeamId(teamId: Long): List<Long>

    @Modifying
    @Query("UPDATE Channel c SET c.projectId = NULL WHERE c.projectId = :projectId")
    fun unassignProject(projectId: Long): Int
}
