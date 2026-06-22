package com.cowork.channel.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelMember
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.event.ChannelMembershipSyncPublisher
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import team.themoment.sdk.exception.ExpectedException

class DmChannelServiceTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val channelMembershipSyncPublisher = mockk<ChannelMembershipSyncPublisher>(relaxed = true)
    private val transactionTemplate = mockk<TransactionTemplate>()

    private val service =
        DmChannelService(
            channelRepository,
            channelMemberRepository,
            channelMembershipSyncPublisher,
            transactionTemplate,
        )

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.initSynchronization()
        every { transactionTemplate.execute(any<TransactionCallback<Any>>()) } answers {
            firstArg<TransactionCallback<Any>>().doInTransaction(mockk(relaxed = true))
        }
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clear()
    }

    private fun dmChannel(id: Long = 1L, dmKey: String = "1:2", createdBy: Long = 1L) = Channel(
        id = id, teamId = null, name = "DM", type = ChannelType.DM, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = true, createdBy = createdBy, dmKey = dmKey,
    )

    @Test
    fun `openDm은 자기 자신과의 DM이면 BAD_REQUEST`() {
        val ex = assertThrows(ExpectedException::class.java) {
            service.openDm(7L, 7L)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `openDm은 기존 DM 채널이 있으면 생성 없이 그대로 반환`() {
        val existing = dmChannel()
        every { channelRepository.findByDmKey("1:2") } returns existing

        val result = service.openDm(2L, 1L)

        assertEquals(existing.id, result.id)
        verify(exactly = 0) { channelRepository.save(any()) }
    }

    @Test
    fun `openDm은 없으면 teamId 없는 DM 채널과 멤버 2명을 생성`() {
        every { channelRepository.findByDmKey("1:2") } returns null
        val savedChannel = slot<Channel>()
        every { channelRepository.save(capture(savedChannel)) } answers { savedChannel.captured }
        val savedMembers = mutableListOf<ChannelMember>()
        every { channelMemberRepository.save(capture(savedMembers)) } answers { savedMembers.last() }

        val result = service.openDm(2L, 1L)

        assertEquals(ChannelType.DM.name, result.type)
        assertEquals(null, result.teamId)
        assertEquals("1:2", savedChannel.captured.dmKey)
        assertEquals(listOf(2L, 1L), savedMembers.map { it.userId })
    }

    @Test
    fun `openDm은 동시 생성 경합 시 유니크 충돌을 잡고 기존 채널을 재조회해 반환`() {
        val existing = dmChannel()
        every { channelRepository.findByDmKey("1:2") } returnsMany listOf(null, existing)
        every { transactionTemplate.execute(any<TransactionCallback<Any>>()) } throws
            DataIntegrityViolationException("duplicate dm_key")

        val result = service.openDm(1L, 2L)

        assertEquals(existing.id, result.id)
    }
}
