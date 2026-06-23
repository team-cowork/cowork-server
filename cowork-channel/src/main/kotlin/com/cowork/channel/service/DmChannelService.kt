package com.cowork.channel.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelMember
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.dto.ChannelResponse
import com.cowork.channel.event.ChannelMembershipSyncPublisher
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import com.cowork.channel.support.afterCommit
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.themoment.sdk.exception.ExpectedException
import kotlin.math.max
import kotlin.math.min

@Service
class DmChannelService(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelMembershipSyncPublisher: ChannelMembershipSyncPublisher,
    private val transactionTemplate: TransactionTemplate,
) {

    /**
     * 두 사용자 간 DM 채널을 연다 (멱등).
     * 이미 존재하면 기존 채널을 반환하고, 없으면 생성한다.
     * 동시 생성 경합은 dm_key 유니크 제약 충돌 시 재조회로 해소한다.
     */
    fun openDm(userId: Long, targetUserId: Long): ChannelResponse {
        if (targetUserId == userId) {
            throw ExpectedException("자기 자신과는 DM을 만들 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        val dmKey = dmKeyOf(userId, targetUserId)
        channelRepository.findByDmKey(dmKey)?.let { return ChannelResponse.of(it) }

        return try {
            transactionTemplate.execute { createDm(userId, targetUserId, dmKey) }!!
        } catch (e: DataIntegrityViolationException) {
            val existing = channelRepository.findByDmKey(dmKey) ?: throw e
            ChannelResponse.of(existing)
        }
    }

    private fun createDm(creatorId: Long, targetUserId: Long, dmKey: String): ChannelResponse {
        val channel = channelRepository.save(
            Channel(
                teamId = null,
                name = "DM",
                type = ChannelType.DM,
                viewType = ChannelViewType.TEXT,
                description = null,
                isPrivate = true,
                createdBy = creatorId,
                dmKey = dmKey,
            ),
        )
        val members = listOf(creatorId, targetUserId).map { memberId ->
            channelMemberRepository.save(ChannelMember(channelId = channel.id, userId = memberId))
        }
        afterCommit { channelMembershipSyncPublisher.publishChannelSnapshot(channel, members) }
        return ChannelResponse.of(channel)
    }

    private fun dmKeyOf(userId: Long, targetUserId: Long): String =
        "${min(userId, targetUserId)}:${max(userId, targetUserId)}"
}
