package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.UpdateProjectMemberRoleService
import com.cowork.project.domain.project.service.support.ProjectEnumParser
import com.cowork.project.domain.project.service.support.ProjectMemberLookupSupport
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.presentation.data.request.UpdateProjectMemberRoleReqDto
import com.cowork.project.domain.projectMember.presentation.data.response.ProjectMemberResDto
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class UpdateProjectMemberRoleServiceImpl(
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectMemberLookupSupport: ProjectMemberLookupSupport,
    private val projectEnumParser: ProjectEnumParser,
) : UpdateProjectMemberRoleService {

    override fun updateMemberRole(
        userId: Long,
        projectId: Long,
        memberId: Long,
        request: UpdateProjectMemberRoleReqDto,
    ): ProjectMemberResDto {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectOwner(project, userId)
        val member = projectMemberLookupSupport.findMemberOrThrow(memberId)

        if (member.projectId != projectId) {
            throw ExpectedException("해당 프로젝트의 멤버가 아닙니다.", HttpStatus.BAD_REQUEST)
        }

        val role = projectEnumParser.parseRole(request.role)
        if (role == ProjectMemberRole.OWNER) {
            throw ExpectedException("OWNER 역할은 역할 변경으로 부여할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        if (member.role == ProjectMemberRole.OWNER) {
            throw ExpectedException("OWNER의 역할은 변경할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }

        member.updateRole(role)

        return ProjectMemberResDto.of(member)
    }
}
