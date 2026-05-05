package com.cowork.channel.consumer

import com.cowork.channel.domain.TeamMembership
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import com.cowork.channel.repository.TeamMembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChannelLifecycleHandler(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val teamMembershipRepository: TeamMembershipRepository,
) {
    private val log = LoggerFactory.getLogger(ChannelLifecycleHandler::class.java)

    @Transactional
    fun onMemberInvited(teamId: Long, userIds: List<Long>, role: String) {
        val existing = teamMembershipRepository.findAllByTeamIdAndUserIdIn(teamId, userIds).map { it.userId }.toSet()
        val newMemberships = userIds
            .filter { it !in existing }
            .map { TeamMembership(teamId = teamId, userId = it, role = role) }
        if (newMemberships.isNotEmpty()) {
            teamMembershipRepository.saveAll(newMemberships)
        }
        log.info("MEMBER_INVITED 처리 완료 [teamId={}, userIds={}]", teamId, userIds)
    }

    @Transactional
    fun onRoleChanged(teamId: Long, userId: Long, newRole: String) {
        val membership = teamMembershipRepository.findByTeamIdAndUserId(teamId, userId) ?: return
        membership.role = newRole
        log.info("ROLE_CHANGED 처리 완료 [teamId={}, userId={}, newRole={}]", teamId, userId, newRole)
    }

    @Transactional
    fun onTeamDeleted(teamId: Long) {
        teamMembershipRepository.deleteAllByTeamId(teamId)

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
        teamMembershipRepository.deleteByTeamIdAndUserId(teamId, targetUserId)

        val creatorOf = channelRepository.findAllByTeamIdAndCreatedByOrderByIdAsc(teamId, targetUserId)
        val otherIds = channelRepository.findIdsByTeamIdAndCreatedByNot(teamId, targetUserId)

        if (creatorOf.isNotEmpty()) {
            channelRepository.deleteAll(creatorOf)
        }
        if (otherIds.isNotEmpty()) {
            channelMemberRepository.deleteAllByUserIdAndChannelIdIn(targetUserId, otherIds)
        }
        log.info(
            "MEMBER_REMOVED 처리 [teamId={}, userId={}, channelsDeleted={}, membershipsRemoved={}]",
            teamId, targetUserId, creatorOf.size, otherIds.size,
        )
    }

    @Transactional
    fun onUserDeleted(userId: Long) {
        val ownedChannels = channelRepository.findAllByCreatedBy(userId)
        if (ownedChannels.isNotEmpty()) {
            channelRepository.deleteAll(ownedChannels)
        }
        channelMemberRepository.deleteAllByUserId(userId)
        log.info("USER_DELETED 처리 [userId={}, channelsDeleted={}]", userId, ownedChannels.size)
    }
}
