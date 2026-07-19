package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.service.AddProjectMemberService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.support.ProjectEnumParser
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.presentation.data.request.AddProjectMemberReqDto
import com.cowork.project.domain.projectMember.presentation.data.response.ProjectMemberResDto
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class AddProjectMemberServiceImpl(
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectEnumParser: ProjectEnumParser,
) : AddProjectMemberService {

    @Transactional
    override fun execute(userId: Long, projectId: Long, request: AddProjectMemberReqDto): ProjectMemberResDto {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectOwner(project, userId)

        projectAccessGuard.teamRoleOf(project.teamId, request.userId)
            ?: throw ExpectedException("추가 대상이 팀 멤버가 아닙니다.", HttpStatus.BAD_REQUEST)

        val role = projectEnumParser.parseRole(request.role)
        if (role == ProjectMemberRole.OWNER) {
            throw ExpectedException("OWNER 역할은 멤버 추가로 부여할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }

        val existingMember = projectMemberRepository.findByProjectIdAndUserId(projectId, request.userId)
        if (existingMember != null) {
            throw ExpectedException("이미 프로젝트에 참여 중인 사용자입니다.", HttpStatus.CONFLICT)
        }

        val member = projectMemberRepository.save(
            ProjectMember(
                projectId = projectId,
                userId = request.userId,
                role = role,
            ),
        )

        return ProjectMemberResDto.of(member)
    }
}
