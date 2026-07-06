package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.service.QueryProjectMemberService
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class QueryProjectMemberServiceImpl(private val projectMemberRepository: ProjectMemberRepository) :
    QueryProjectMemberService {

    override fun execute(projectId: Long, userId: Long): Boolean =
        projectMemberRepository.findByProjectIdAndUserId(projectId, userId) != null
}
