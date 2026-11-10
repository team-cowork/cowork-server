package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelArchivePermissionSupport
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

class ArchiveChannelServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>()
    private val permissionSupport = mockk<ChannelArchivePermissionSupport>()
    private val channelEventPublisher = mockk<ChannelEventPublisher>(relaxed = true)
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)

    private val archiveService = ArchiveChannelServiceImpl(channelAccessGuard, permissionSupport, channelEventPublisher)
    private val unarchiveService =
        UnarchiveChannelServiceImpl(channelAccessGuard, permissionSupport, channelEventPublisher)

    private val channel = Channel(
        id = 1L,
        teamId = 10L,
        name = "general",
        type = ChannelType.TEXT,
        viewType = ChannelViewType.TEXT,
        description = null,
        createdBy = 1L,
    )

    @BeforeEach
    fun setUp() {
        every { channelRepository.findByIdForUpdate(1L) } returns channel
        every { permissionSupport.requireArchivePermission(channel, 7L) } just runs
    }

    @Test
    fun `채널을 보관하고 보관 상태를 이벤트로 발행한다`() {
        val response = archiveService.execute(7L, 1L)

        assertTrue(response.archived)
        assertEquals(7L, response.archivedBy)
        verify { channelEventPublisher.publishUpdated(channel, any()) }
    }

    @Test
    fun `이미 보관된 채널을 보관하면 CONFLICT`() {
        channel.archive(7L, LocalDateTime.of(2026, 11, 10, 9, 0))

        val ex = assertThrows(ExpectedException::class.java) { archiveService.execute(7L, 1L) }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        verify(exactly = 0) { channelEventPublisher.publishUpdated(any(), any()) }
    }

    @Test
    fun `권한이 없으면 상태를 바꾸지 않는다`() {
        every { permissionSupport.requireArchivePermission(channel, 7L) } throws
            ExpectedException("프로젝트 채널 보관 권한이 없습니다.", HttpStatus.FORBIDDEN)

        assertThrows(ExpectedException::class.java) { archiveService.execute(7L, 1L) }

        assertFalse(channel.isArchived)
        verify(exactly = 0) { channelEventPublisher.publishUpdated(any(), any()) }
    }

    @Test
    fun `보관된 채널을 복원하고 이벤트로 발행한다`() {
        channel.archive(7L, LocalDateTime.of(2026, 11, 10, 9, 0))

        val response = unarchiveService.execute(7L, 1L)

        assertFalse(response.archived)
        verify { channelEventPublisher.publishUpdated(channel, any()) }
    }

    @Test
    fun `보관되지 않은 채널을 복원하면 CONFLICT`() {
        val ex = assertThrows(ExpectedException::class.java) { unarchiveService.execute(7L, 1L) }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }
}
