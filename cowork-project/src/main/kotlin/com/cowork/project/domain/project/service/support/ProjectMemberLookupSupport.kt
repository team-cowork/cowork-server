package com.cowork.project.domain.project.service.support

import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class ProjectMemberLookupSupport(private val projectMemberRepository: ProjectMemberRepository) {

    fun findMemberOrThrow(memberId: Long): ProjectMember = projectMemberRepository.findById(memberId).orElseThrow {
        ExpectedException("프로젝트 멤버를 찾을 수 없습니다. id=$memberId", HttpStatus.NOT_FOUND)
    }
}
