package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.service.IsProjectMemberService
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class IsProjectMemberServiceImpl(
    private val projectMemberRepository: ProjectMemberRepository,
) : IsProjectMemberService {

    override fun isMember(projectId: Long, userId: Long): Boolean =
        projectMemberRepository.findByProjectIdAndUserId(projectId, userId) != null
}
