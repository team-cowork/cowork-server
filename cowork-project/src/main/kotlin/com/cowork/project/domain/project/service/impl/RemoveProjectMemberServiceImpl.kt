package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.RemoveProjectMemberService
import com.cowork.project.domain.project.service.support.ProjectMemberLookupSupport
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class RemoveProjectMemberServiceImpl(
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectMemberLookupSupport: ProjectMemberLookupSupport,
    private val projectMemberEventPublisher: ProjectMemberEventPublisher,
) : RemoveProjectMemberService {

    @Transactional
    override fun execute(userId: Long, projectId: Long, memberId: Long) {
        val project = projectAccessGuard.findProjectForUpdateOrThrow(projectId)
        projectAccessGuard.requireProjectOwner(project, userId)
        val member = projectMemberLookupSupport.findMemberForUpdateOrThrow(memberId)

        if (member.projectId != projectId) {
            throw ExpectedException("해당 프로젝트의 멤버가 아닙니다.", HttpStatus.BAD_REQUEST)
        }

        if (member.role == ProjectMemberRole.OWNER) {
            throw ExpectedException("OWNER는 제거할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }

        projectMemberEventPublisher.publishRemoved(member)
        projectMemberRepository.delete(member)
    }
}
