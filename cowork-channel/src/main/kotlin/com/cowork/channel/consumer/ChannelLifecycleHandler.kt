package com.cowork.channel.consumer

import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChannelLifecycleHandler(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
) {
    private val log = LoggerFactory.getLogger(ChannelLifecycleHandler::class.java)

    @Transactional
    fun onTeamDeleted(teamId: Long) {
        val channels = channelRepository.findAllByTeamIdOrderByIdAsc(teamId)
        if (channels.isEmpty()) {
            log.info("TEAM_DELETED 처리: 대상 채널 없음 [teamId={}]", teamId)
            return
        }
        channelRepository.deleteAll(channels)
        log.info("TEAM_DELETED 처리 완료 [teamId={}, deletedChannels={}]", teamId, channels.size)
    }

    @Transactional
    fun onMemberRemovedFromTeam(teamId: Long, targetUserId: Long) {
        val teamChannels = channelRepository.findAllByTeamIdOrderByIdAsc(teamId)
        if (teamChannels.isEmpty()) return

        val (creatorOf, others) = teamChannels.partition { it.createdBy == targetUserId }

        if (creatorOf.isNotEmpty()) {
            channelRepository.deleteAll(creatorOf)
        }
        if (others.isNotEmpty()) {
            channelMemberRepository.deleteAllByUserIdAndChannelIdIn(targetUserId, others.map { it.id })
        }
        log.info(
            "MEMBER_REMOVED 처리 [teamId={}, userId={}, channelsDeleted={}, membershipsRemoved={}]",
            teamId, targetUserId, creatorOf.size, others.size,
        )
    }

    @Transactional
    fun onUserDeleted(userId: Long) {
        val ownedChannels = channelRepository.findAll().filter { it.createdBy == userId }
        if (ownedChannels.isNotEmpty()) {
            channelRepository.deleteAll(ownedChannels)
        }
        channelMemberRepository.deleteAllByUserId(userId)
        log.info("USER_DELETED 처리 [userId={}, channelsDeleted={}]", userId, ownedChannels.size)
    }
}
