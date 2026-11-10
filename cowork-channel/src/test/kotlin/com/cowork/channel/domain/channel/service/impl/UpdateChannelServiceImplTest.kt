package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.presentation.data.request.UpdateChannelRequest
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.repository.DmParticipantRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import com.cowork.channel.domain.project.entity.ProjectProjection
import com.cowork.channel.domain.project.repository.ProjectProjectionRepository
import com.cowork.channel.global.projection.ProjectionReadinessGate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional

class UpdateChannelServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val dmParticipantRepository = mockk<DmParticipantRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()
    private val channelEventPublisher = mockk<ChannelEventPublisher>(relaxed = true)
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val permissionEvaluator = mockk<ChannelMessageReadPolicyEvaluator>(relaxed = true)
    private val channelPermissionSupport = ChannelPermissionSupport(
        dmParticipantRepository,
        teamPermission,
        permissionEvaluator,
    )

    private val projectProjectionRepository = mockk<ProjectProjectionRepository>()
    private val projectionReadinessGate = mockk<ProjectionReadinessGate>(relaxed = true)

    private val service = UpdateChannelServiceImpl(
        channelEventPublisher,
        channelAccessGuard,
        channelPermissionSupport,
        projectProjectionRepository,
        projectionReadinessGate,
    )

    private fun project(projectId: Long = 5L, teamId: Long = 100L, deleted: Boolean = false) =
        Optional.of(ProjectProjection(projectId, teamId, deleted, Instant.EPOCH))

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

    private fun dmChannel(id: Long = 1L, createdBy: Long = 1L) = Channel(
        id = id, teamId = null, name = "DM", type = ChannelType.DM, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = true, createdBy = createdBy, dmKey = "1:2",
    )

    @Test
    fun `보관된 채널은 공개 여부만 바꿀 수 있다`() {
        val ch = channel(createdBy = 99L).apply { archive(1L, LocalDateTime.of(2026, 11, 10, 9, 0)) }
        every { channelRepository.findByIdForUpdate(1L) } returns ch

        val response = service.execute(1L, 1L, UpdateChannelRequest(isPrivate = true), updateProjectId = false)

        assertEquals(true, response.isPrivate)
        verify { channelEventPublisher.publishUpdated(ch, any()) }
    }

    @Test
    fun `보관된 채널의 이름이나 프로젝트를 바꾸면 CONFLICT`() {
        val ch = channel(createdBy = 99L).apply { archive(1L, LocalDateTime.of(2026, 11, 10, 9, 0)) }
        every { channelRepository.findByIdForUpdate(1L) } returns ch

        val renamed = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L, UpdateChannelRequest(name = "renamed"), updateProjectId = false)
        }
        val moved = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L, UpdateChannelRequest(), updateProjectId = true)
        }

        assertEquals(HttpStatus.CONFLICT, renamed.statusCode)
        assertEquals(HttpStatus.CONFLICT, moved.statusCode)
        assertEquals("ch", ch.name)
    }

    @Test
    fun `updateChannel은 팀 OWNER 등가 권한으로 통과`() {
        val ch = channel(createdBy = 99L)
        every { channelRepository.findByIdForUpdate(1L) } returns ch
        every { permissionEvaluator.requirePermission(100L, 1L, ch, "channel_manage") } returns Unit

        val res = service.execute(1L, 1L, UpdateChannelRequest(name = "newName"), false)
        assertEquals("newName", res.name)
    }

    @Test
    fun `updateChannel은 비생성자 + 팀 일반멤버이면 FORBIDDEN`() {
        val ch = channel(createdBy = 99L)
        every { channelRepository.findByIdForUpdate(1L) } returns ch
        every { permissionEvaluator.requirePermission(100L, 1L, ch, "channel_manage") } throws
            ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L, UpdateChannelRequest(name = "x"), false)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `updateChannel은 name이 null이면 기존 이름을 유지하고 다른 필드만 변경`() {
        val ch = channel(createdBy = 1L)
        every { channelRepository.findByIdForUpdate(1L) } returns ch
        every { projectProjectionRepository.findById(5L) } returns project()

        val res = service.execute(
            1L,
            1L,
            UpdateChannelRequest(name = null, description = "changed", projectId = 5L),
            true,
        )

        assertEquals("ch", res.name)
        assertEquals("changed", ch.description)
        assertEquals(5L, res.projectId)
    }

    @Test
    fun `updateChannel은 updateProjectId=true이면 projectId를 할당함`() {
        val ch = channel(createdBy = 1L)
        every { channelRepository.findByIdForUpdate(1L) } returns ch
        every { projectProjectionRepository.findById(5L) } returns project()

        val res = service.execute(1L, 1L, UpdateChannelRequest(projectId = 5L), true)
        assertEquals(5L, res.projectId)
    }

    @Test
    fun `updateChannel은 updateProjectId=false이면 기존 projectId를 유지함`() {
        val ch = channel(createdBy = 1L, projectId = 99L)
        every { channelRepository.findByIdForUpdate(1L) } returns ch

        val res = service.execute(1L, 1L, UpdateChannelRequest(), false)
        assertEquals(99L, res.projectId)
    }

    @Test
    fun `updateChannel은 updateProjectId=true에 projectId=null이면 프로젝트 귀속 해제`() {
        val ch = channel(createdBy = 1L, projectId = 99L)
        every { channelRepository.findByIdForUpdate(1L) } returns ch

        val res = service.execute(1L, 1L, UpdateChannelRequest(projectId = null), true)
        assertEquals(null, res.projectId)
    }

    @Test
    fun `비공개 전환으로 요청자 접근이 사라지면 채널 변경과 이벤트 발행을 거절한다`() {
        val ch = channel(createdBy = 99L, projectId = 99L)
        every { channelRepository.findByIdForUpdate(1L) } returns ch
        every { permissionEvaluator.requirePrivateTransitionAccess(100L, 1L, ch) } throws
            ExpectedException("비공개 전환 후에도 접근할 수 있도록 채널 역할 권한을 먼저 설정해야 합니다.", HttpStatus.BAD_REQUEST)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L, UpdateChannelRequest(name = "new", isPrivate = true, projectId = 5L), true)
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        assertEquals(false, ch.isPrivate)
        assertEquals("ch", ch.name)
        assertEquals(99L, ch.projectId)
        verify(exactly = 0) { channelEventPublisher.publishUpdated(any(), any()) }
    }

    @Test
    fun `전환 후에도 접근 가능한 일반 관리자는 채널을 비공개로 변경한다`() {
        val ch = channel(createdBy = 99L)
        every { channelRepository.findByIdForUpdate(1L) } returns ch
        every { permissionEvaluator.requirePrivateTransitionAccess(100L, 1L, ch) } returns Unit

        val result = service.execute(1L, 1L, UpdateChannelRequest(isPrivate = true), false)

        assertEquals(true, result.isPrivate)
        verify(exactly = 1) { permissionEvaluator.requirePrivateTransitionAccess(100L, 1L, ch) }
        verify(exactly = 1) { channelEventPublisher.publishUpdated(ch, any()) }
    }

    @Test
    fun `updateChannel은 DM 채널이면 BAD_REQUEST`() {
        every { channelRepository.findByIdForUpdate(1L) } returns dmChannel()

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L, UpdateChannelRequest(name = "x"), false)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `updateChannel은 다른 팀 프로젝트를 연결하면 NOT_FOUND이고 변경하지 않음`() {
        val ch = channel(createdBy = 1L, projectId = 99L)
        every { channelRepository.findByIdForUpdate(1L) } returns ch
        every { projectProjectionRepository.findById(5L) } returns project(teamId = 200L)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L, UpdateChannelRequest(name = "new", projectId = 5L), true)
        }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        assertEquals("ch", ch.name)
        assertEquals(99L, ch.projectId)
        verify(exactly = 0) { channelEventPublisher.publishUpdated(any(), any()) }
    }

    @Test
    fun `updateChannel은 삭제된 프로젝트를 연결하면 NOT_FOUND`() {
        every { channelRepository.findByIdForUpdate(1L) } returns channel(createdBy = 1L)
        every { projectProjectionRepository.findById(5L) } returns project(deleted = true)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L, UpdateChannelRequest(projectId = 5L), true)
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `updateChannel은 프로젝트 projection이 없으면 SERVICE_UNAVAILABLE`() {
        every { channelRepository.findByIdForUpdate(1L) } returns channel(createdBy = 1L)
        every { projectProjectionRepository.findById(5L) } returns Optional.empty()

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L, UpdateChannelRequest(projectId = 5L), true)
        }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.statusCode)
    }

    @Test
    fun `updateChannel은 projection 준비 전이면 SERVICE_UNAVAILABLE이고 변경하지 않음`() {
        val ch = channel(createdBy = 1L)
        every { channelRepository.findByIdForUpdate(1L) } returns ch
        every { projectionReadinessGate.requireReady() } throws
            ExpectedException("프로젝트 동기화 정보가 준비되지 않았습니다.", HttpStatus.SERVICE_UNAVAILABLE)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L, UpdateChannelRequest(name = "new", projectId = 5L), true)
        }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.statusCode)
        assertEquals("ch", ch.name)
        assertEquals(null, ch.projectId)
        verify(exactly = 0) { channelEventPublisher.publishUpdated(any(), any()) }
    }

    @Test
    fun `updateChannel은 현재와 같은 projectId이면 프로젝트를 다시 확인하지 않음`() {
        val ch = channel(createdBy = 1L, projectId = 5L)
        every { channelRepository.findByIdForUpdate(1L) } returns ch

        val res = service.execute(1L, 1L, UpdateChannelRequest(name = "new", projectId = 5L), true)

        assertEquals("new", res.name)
        assertEquals(5L, res.projectId)
        verify(exactly = 0) { projectProjectionRepository.findById(any()) }
    }
}
