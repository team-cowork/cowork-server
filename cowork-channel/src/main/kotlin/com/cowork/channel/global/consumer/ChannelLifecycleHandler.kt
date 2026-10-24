package com.cowork.channel.global.consumer

import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.membership.entity.TeamMembership
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import com.cowork.channel.global.projection.toProjectionPrecision
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ChannelLifecycleHandler(
    private val channelRepository: ChannelRepository,
    private val teamMembershipRepository: TeamMembershipRepository,
    private val channelEventPublisher: ChannelEventPublisher,
) {
    private val log = LoggerFactory.getLogger(ChannelLifecycleHandler::class.java)

    @Transactional
    fun onMemberUpsert(teamId: Long, userId: Long, role: String, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val membership = teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(teamId, userId)
            ?: TeamMembership(teamId = teamId, userId = userId, role = role, sourceOccurredAt = version)
        val existingVersion = membership.sourceOccurredAt.toProjectionPrecision()
        if (existingVersion.isAfter(version) ||
            (existingVersion == version && !membership.active)
        ) {
            return
        }

        membership.applyUpsert(role, version)
        teamMembershipRepository.save(membership)
        log.info("team.member UPSERT 처리 [teamId={}, userId={}, role={}]", teamId, userId, role)
    }

    @Transactional
    fun onTeamDeleted(teamId: Long, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val memberships = teamMembershipRepository.findAllByTeamIdForUpdateOrderByIdAsc(teamId)
        memberships.filter { !it.sourceOccurredAt.toProjectionPrecision().isAfter(version) }
            .forEach { it.markDeleted(version) }
        if (memberships.isNotEmpty()) teamMembershipRepository.saveAll(memberships)

        val channels = channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(teamId)
        if (channels.isEmpty()) {
            log.info("Skipped TEAM_DELETED event: no channels to delete [teamId={}]", teamId)
            return
        }
        channels.forEach { channelEventPublisher.publishDeleted(it, version) }
        channelRepository.deleteAll(channels)
        log.info("Processed TEAM_DELETED event [teamId={}, deletedChannels={}]", teamId, channels.size)
    }

    @Transactional
    fun onMemberRemovedFromTeam(teamId: Long, targetUserId: Long, role: String, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val membership = teamMembershipRepository.findStateByTeamIdAndUserIdForUpdate(teamId, targetUserId)
            ?: TeamMembership(
                teamId = teamId,
                userId = targetUserId,
                role = role,
                active = false,
                sourceOccurredAt = version,
            )
        val existingVersion = membership.sourceOccurredAt.toProjectionPrecision()
        if (existingVersion.isAfter(version) ||
            (existingVersion == version && !membership.active)
        ) {
            return
        }
        membership.markDeleted(version)
        teamMembershipRepository.save(membership)

        val teamChannels = channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(teamId)
        val creatorOf = teamChannels.filter { it.createdBy == targetUserId }
        creatorOf.forEach { channelEventPublisher.publishDeleted(it, version) }
        if (creatorOf.isNotEmpty()) channelRepository.deleteAll(creatorOf)
        log.info(
            "MEMBER_REMOVED 처리 [teamId={}, userId={}, channelsDeleted={}]",
            teamId,
            targetUserId,
            creatorOf.size,
        )
    }
}
