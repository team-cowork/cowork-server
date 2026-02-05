package com.cowork.team

import com.cowork.global.error.BusinessException
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamPermission
import com.cowork.team.domain.TeamRole
import com.cowork.team.dto.CreateTeamRoleRequest
import com.cowork.team.dto.UpdateTeamRoleRequest
import com.cowork.team.repository.TeamRoleAssignmentRepository
import com.cowork.team.repository.TeamRoleRepository
import com.cowork.team.service.TeamAccessChecker
import com.cowork.team.service.TeamRoleService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

class TeamRoleServiceTest {

    private val roleRepository = mockk<TeamRoleRepository>(relaxed = true)
    private val assignmentRepository = mockk<TeamRoleAssignmentRepository>(relaxed = true)
    private val accessChecker = mockk<TeamAccessChecker>()
    private val service = TeamRoleService(roleRepository, assignmentRepository, accessChecker)

    private val role = TeamRole(teamId = 1, name = "디자인", priority = 1, id = 30)

    @BeforeEach
    fun allowManageRoles() {
        every { accessChecker.requirePermission(1, 2, TeamPermission.MANAGE_ROLES) } returns
            TeamMember(teamId = 1, userId = 2, role = TeamMemberRole.ADMIN)
    }

    @Test
    fun `색상을 지정하지 않으면 기본 색상으로 역할을 만든다`() {
        val saved = slot<TeamRole>()
        every { roleRepository.existsByTeamIdAndName(1, "기획") } returns false
        every { roleRepository.save(capture(saved)) } answers { saved.captured }

        val response = service.create(1, 2, CreateTeamRoleRequest(name = "기획"))

        assertEquals(TeamRole.DEFAULT_COLOR, response.colorHex)
    }

    @Test
    fun `같은 이름의 역할은 만들 수 없다`() {
        every { roleRepository.existsByTeamIdAndName(1, "디자인") } returns true

        val exception = assertThrows<BusinessException> { service.create(1, 2, CreateTeamRoleRequest(name = "디자인")) }

        assertEquals(HttpStatus.CONFLICT, exception.status)
    }

    @Test
    fun `다른 역할과 이름이 겹치면 수정할 수 없다`() {
        every { roleRepository.findByIdAndTeamId(30, 1) } returns role
        every { roleRepository.existsByTeamIdAndName(1, "기획") } returns true

        assertThrows<BusinessException> { service.update(1, 2, 30, UpdateTeamRoleRequest(name = "기획")) }
    }

    @Test
    fun `이름을 그대로 두면 중복 검사 없이 나머지 항목을 수정한다`() {
        every { roleRepository.findByIdAndTeamId(30, 1) } returns role

        val response = service.update(
            1, 2, 30,
            UpdateTeamRoleRequest(name = "디자인", priority = 5, permissions = setOf(TeamPermission.MANAGE_CHANNELS)),
        )

        assertEquals(5, response.priority)
        assertEquals(setOf(TeamPermission.MANAGE_CHANNELS), response.permissions)
        verify(exactly = 0) { roleRepository.existsByTeamIdAndName(any(), any()) }
    }

    @Test
    fun `역할을 삭제하면 부여 정보도 함께 삭제한다`() {
        every { roleRepository.findByIdAndTeamId(30, 1) } returns role

        service.delete(1, 2, 30)

        verify { assignmentRepository.deleteAllByRoleId(30) }
        verify { roleRepository.delete(role) }
    }

    @Test
    fun `다른 팀의 역할은 찾을 수 없다`() {
        every { roleRepository.findByIdAndTeamId(30, 1) } returns null

        val exception = assertThrows<BusinessException> { service.delete(1, 2, 30) }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }
}
