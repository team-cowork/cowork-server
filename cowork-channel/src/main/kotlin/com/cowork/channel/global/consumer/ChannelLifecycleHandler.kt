package com.cowork.channel.global.consumer

import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.event.ChannelMemberEventPublisher
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
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
    private val channelMemberRepository: ChannelMemberRepository,
    private val teamMembershipRepository: TeamMembershipRepository,
    private val channelEventPublisher: ChannelEventPublisher,
    private val channelMemberEventPublisher: ChannelMemberEventPublisher,
) {
    private val log = LoggerFactory.getLogger(ChannelLifecycleHandler::class.java)

    @Transactional
    fun onMemberUpsert(teamId: Long, userId: Long, role: String, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val membership = teamMembershipRepository.findStateByTeamIdAndUserId(teamId, userId)
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
        val memberships = teamMembershipRepository.findAllByTeamId(teamId)
        memberships.filter { !it.sourceOccurredAt.toProjectionPrecision().isAfter(version) }
            .forEach { it.markDeleted(version) }
        if (memberships.isNotEmpty()) teamMembershipRepository.saveAll(memberships)

        val channels = channelRepository.findAllByTeamIdOrderByPositionAscIdAsc(teamId)
        if (channels.isEmpty()) {
            log.info("Skipped TEAM_DELETED event: no channels to delete [teamId={}]", teamId)
            return
        }
        val membersByChannel = channels.associateWith { channelMemberRepository.findByChannelId(it.id) }
        channelRepository.deleteAll(channels)
        membersByChannel.forEach { (channel, members) ->
            members.forEach { member ->
                channelMemberEventPublisher.publishLeave(
                    channel.id,
                    channel.teamId,
                    member.userId,
                    channel.type.name,
                    occurredAt = version,
                )
            }
            channelEventPublisher.publishDeleted(channel, version)
        }
        log.info("Processed TEAM_DELETED event [teamId={}, deletedChannels={}]", teamId, channels.size)
    }

    @Transactional
    fun onMemberRemovedFromTeam(teamId: Long, targetUserId: Long, role: String, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val membership = teamMembershipRepository.findStateByTeamIdAndUserId(teamId, targetUserId)
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

        val creatorOf = channelRepository.findAllByTeamIdAndCreatedByOrderByIdAsc(teamId, targetUserId)
        val otherIds = channelRepository.findIdsByTeamIdAndCreatedByNot(teamId, targetUserId)
        val deletedMembers = creatorOf.associateWith { channelMemberRepository.findByChannelId(it.id) }
        val otherChannels = channelRepository.findAllById(otherIds).associateBy { it.id }

        if (creatorOf.isNotEmpty()) {
            channelRepository.deleteAll(creatorOf)
        }
        if (otherIds.isNotEmpty()) {
            channelMemberRepository.deleteAllByUserIdAndChannelIdIn(targetUserId, otherIds)
        }
        deletedMembers.forEach { (channel, members) ->
            members.forEach { member ->
                channelMemberEventPublisher.publishLeave(
                    channel.id,
                    channel.teamId,
                    member.userId,
                    channel.type.name,
                    occurredAt = version,
                )
            }
            channelEventPublisher.publishDeleted(channel, version)
        }
        otherIds.forEach { channelId ->
            otherChannels[channelId]?.let { channel ->
                channelMemberEventPublisher.publishLeave(
                    channel.id,
                    channel.teamId,
                    targetUserId,
                    channel.type.name,
                    occurredAt = version,
                )
            }
        }
        log.info(
            "MEMBER_REMOVED 처리 [teamId={}, userId={}, channelsDeleted={}, membershipsRemoved={}]",
            teamId,
            targetUserId,
            creatorOf.size,
            otherIds.size,
        )
    }

    @Transactional
    fun onUserDeleted(userId: Long, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        // DM 채널은 상대방의 대화 기록 보존을 위해 삭제하지 않는다.
        val ownedChannels = channelRepository.findAllByCreatedBy(userId).filter { it.type != ChannelType.DM }
        val deletedMembers = ownedChannels.associateWith { channelMemberRepository.findByChannelId(it.id) }
        val userMemberships = channelMemberRepository.findAllByUserId(userId)
        val channelsById = channelRepository.findAllById(userMemberships.map { it.channelId }.distinct())
            .associateBy { it.id }
        if (ownedChannels.isNotEmpty()) {
            channelRepository.deleteAll(ownedChannels)
        }
        channelMemberRepository.deleteAllByUserId(userId)
        val deletedChannelIds = ownedChannels.map { it.id }.toSet()
        deletedMembers.forEach { (channel, members) ->
            members.forEach { member ->
                channelMemberEventPublisher.publishLeave(
                    channel.id,
                    channel.teamId,
                    member.userId,
                    channel.type.name,
                    occurredAt = version,
                )
            }
            channelEventPublisher.publishDeleted(channel, version)
        }
        userMemberships.filterNot { it.channelId in deletedChannelIds }.forEach { member ->
            channelsById[member.channelId]?.let { channel ->
                channelMemberEventPublisher.publishLeave(
                    channel.id,
                    channel.teamId,
                    member.userId,
                    channel.type.name,
                    occurredAt = version,
                )
            }
        }
        log.info("Processed USER_DELETED event [userId={}, channelsDeleted={}]", userId, ownedChannels.size)
    }
}
