package com.cowork.channel.service

import com.cowork.channel.client.ProjectClient
import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelMember
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.dto.AddMemberRequest
import com.cowork.channel.dto.CreateChannelRequest
import com.cowork.channel.dto.UpdateChannelRequest
import com.cowork.channel.event.ChannelEventPublisher
import com.cowork.channel.event.ChannelMemberEventPublisher
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
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class ChannelServiceTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()
    private val channelMemberEventPublisher = mockk<ChannelMemberEventPublisher>(relaxed = true)
    private val channelMembershipSyncPublisher = mockk<ChannelMembershipSyncPublisher>(relaxed = true)
    private val channelEventPublisher = mockk<ChannelEventPublisher>(relaxed = true)
    private val projectClient = mockk<ProjectClient>()
    private val meetingNoteTemplateService = mockk<MeetingNoteTemplateService>(relaxed = true)

    private val service =
        ChannelService(
            channelRepository,
            channelMemberRepository,
            teamPermission,
            channelMemberEventPublisher,
            channelMembershipSyncPublisher,
            channelEventPublisher,
            projectClient,
            meetingNoteTemplateService,
        )

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.initSynchronization()
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clear()
    }

    private fun channel(
        id: Long = 1L,
        teamId: Long = 100L,
        type: ChannelType = ChannelType.TEXT,
        view: ChannelViewType = ChannelViewType.TEXT,
        createdBy: Long = 1L,
        isPrivate: Boolean = false,
        position: Int = 0,
        projectId: Long? = null,
    ) = Channel(
        id = id, teamId = teamId, name = "ch", type = type, viewType = view,
        description = null, isPrivate = isPrivate, position = position, createdBy = createdBy, projectId = projectId,
    )

    @Test
    fun `createChannel은 팀 비멤버이면 FORBIDDEN`() {
        every { teamPermission.requireTeamMember(100L, 7L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows(ExpectedException::class.java) {
            service.createChannel(
                7L,
                CreateChannelRequest(teamId = 100L, name = "n", type = "TEXT", viewType = "WEBHOOK"),
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `createChannel은 type+viewType 모두 저장`() {
        every { teamPermission.requireTeamMember(any(), any()) } returns Unit
        every { channelRepository.findMaxPositionByTeamId(100L) } returns 4
        val saved = slot<Channel>()
        every { channelRepository.save(capture(saved)) } answers { saved.captured }
        every { channelMemberRepository.save(any()) } answers { firstArg() }

        service.createChannel(
            1L,
            CreateChannelRequest(
                teamId = 100L,
                name = "n",
                type = "TEXT",
                viewType = "MEETING_NOTE",
            ),
        )

        assertEquals(ChannelType.TEXT, saved.captured.type)
        assertEquals(ChannelViewType.MEETING_NOTE, saved.captured.viewType)
        assertEquals(5, saved.captured.position)
    }

    @Test
    fun `reorderTeamChannels는 요청 순서대로 position을 갱신함`() {
        val first = channel(id = 1L, position = 0)
        val second = channel(id = 2L, position = 1)
        every { teamPermission.requireTeamMember(100L, 7L) } returns Unit
        every { channelRepository.findAllByTeamIdOrderByPositionAscIdAsc(100L) } returns listOf(first, second)

        val result = service.reorderTeamChannels(7L, 100L, listOf(2L, 1L))

        assertEquals(listOf(2L, 1L), result.map { it.id })
        assertEquals(1, first.position)
        assertEquals(0, second.position)
    }

    @Test
    fun `reorderTeamChannels는 팀 채널 ID 누락 시 BAD_REQUEST`() {
        every { teamPermission.requireTeamMember(100L, 7L) } returns Unit
        every { channelRepository.findAllByTeamIdOrderByPositionAscIdAsc(100L) } returns
            listOf(channel(id = 1L), channel(id = 2L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.reorderTeamChannels(7L, 100L, listOf(1L))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `updateChannel은 팀 OWNER 등가 권한으로 통과`() {
        val ch = channel(createdBy = 99L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { teamPermission.isTeamOwnerOrAdmin(100L, 1L) } returns true

        val res = service.updateChannel(1L, 1L, UpdateChannelRequest(name = "newName"))
        assertEquals("newName", res.name)
    }

    @Test
    fun `updateChannel은 비생성자 + 팀 일반멤버이면 FORBIDDEN`() {
        val ch = channel(createdBy = 99L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { teamPermission.isTeamOwnerOrAdmin(100L, 1L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateChannel(1L, 1L, UpdateChannelRequest(name = "x"))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `updateChannel은 updateProjectId=true이면 projectId를 할당함`() {
        val ch = channel(createdBy = 1L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)

        val res = service.updateChannel(1L, 1L, UpdateChannelRequest(projectId = 5L), updateProjectId = true)
        assertEquals(5L, res.projectId)
    }

    @Test
    fun `updateChannel은 updateProjectId=false이면 기존 projectId를 유지함`() {
        val ch = channel(createdBy = 1L, projectId = 99L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)

        val res = service.updateChannel(1L, 1L, UpdateChannelRequest(), updateProjectId = false)
        assertEquals(99L, res.projectId)
    }

    @Test
    fun `updateChannel은 updateProjectId=true에 projectId=null이면 프로젝트 귀속 해제`() {
        val ch = channel(createdBy = 1L, projectId = 99L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)

        val res = service.updateChannel(1L, 1L, UpdateChannelRequest(projectId = null), updateProjectId = true)
        assertEquals(null, res.projectId)
    }

    @Test
    fun `listProjectChannels는 채널이 있으면 팀 멤버 검증 후 반환함`() {
        val ch = channel(id = 1L, teamId = 100L)
        every { projectClient.getTeamId(5L) } returns 100L
        every { teamPermission.requireTeamMember(100L, 1L) } returns Unit
        every { channelRepository.findAllByProjectIdOrderByIdAsc(5L) } returns listOf(ch)

        val result = service.listProjectChannels(1L, 5L)
        assertEquals(1, result.size)
        verify { teamPermission.requireTeamMember(100L, 1L) }
    }

    @Test
    fun `listProjectChannels는 팀 비멤버이면 FORBIDDEN`() {
        every { projectClient.getTeamId(5L) } returns 100L
        every { teamPermission.requireTeamMember(100L, 1L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows(ExpectedException::class.java) {
            service.listProjectChannels(1L, 5L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `listProjectChannels는 채널이 없으면 빈 리스트를 반환함`() {
        every { projectClient.getTeamId(5L) } returns 100L
        every { teamPermission.requireTeamMember(100L, 1L) } returns Unit
        every { channelRepository.findAllByProjectIdOrderByIdAsc(5L) } returns emptyList()

        val result = service.listProjectChannels(1L, 5L)
        assertEquals(0, result.size)
        verify { teamPermission.requireTeamMember(100L, 1L) }
    }

    @Test
    fun `addMember는 비공개 채널일 때 채널 생성자만 추가 가능`() {
        val ch = channel(createdBy = 99L, isPrivate = true)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { teamPermission.isTeamOwnerOrAdmin(100L, 1L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.addMember(1L, 1L, AddMemberRequest(userId = 50L))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        verify(exactly = 0) { channelMemberRepository.save(any()) }
    }

    @Test
    fun `addMember는 추가 대상이 팀 멤버 아니면 BAD_REQUEST`() {
        val ch = channel(createdBy = 1L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { teamPermission.requireTeamMember(100L, 1L) } returns Unit
        every { teamPermission.isTeamMember(100L, 50L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.addMember(1L, 1L, AddMemberRequest(userId = 50L))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    private fun dmChannel(id: Long = 1L, createdBy: Long = 1L) = Channel(
        id = id, teamId = null, name = "DM", type = ChannelType.DM, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = true, createdBy = createdBy, dmKey = "1:2",
    )

    @Test
    fun `createChannel은 type=DM이면 BAD_REQUEST`() {
        every { teamPermission.requireTeamMember(any(), any()) } returns Unit

        val ex = assertThrows(ExpectedException::class.java) {
            service.createChannel(1L, CreateChannelRequest(teamId = 100L, name = "n", type = "DM", viewType = "TEXT"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `updateChannel은 DM 채널이면 BAD_REQUEST`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateChannel(1L, 1L, UpdateChannelRequest(name = "x"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `deleteChannel은 DM 채널이면 BAD_REQUEST`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())

        val ex = assertThrows(ExpectedException::class.java) {
            service.deleteChannel(1L, 1L)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `addMember는 DM 채널이면 BAD_REQUEST`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())

        val ex = assertThrows(ExpectedException::class.java) {
            service.addMember(1L, 1L, AddMemberRequest(userId = 50L))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        verify(exactly = 0) { channelMemberRepository.save(any()) }
    }

    @Test
    fun `getChannel은 DM 채널이면 채널 멤버에게 허용`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 2L) } returns true

        val res = service.getChannel(2L, 1L)
        assertEquals(null, res.teamId)
        assertEquals(ChannelType.DM.name, res.type)
    }

    @Test
    fun `getChannel은 DM 채널 비멤버이면 FORBIDDEN`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 9L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.getChannel(9L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `addMember 정상 흐름`() {
        val ch = channel(createdBy = 1L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { teamPermission.requireTeamMember(100L, 1L) } returns Unit
        every { teamPermission.isTeamMember(100L, 50L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 50L) } returns false
        val savedMember = slot<ChannelMember>()
        every { channelMemberRepository.save(capture(savedMember)) } answers { savedMember.captured }

        service.addMember(1L, 1L, AddMemberRequest(userId = 50L))
        assertEquals(50L, savedMember.captured.userId)
    }
}
