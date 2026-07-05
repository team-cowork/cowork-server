package com.cowork.project.domain.project.service.support

import com.cowork.project.domain.project.entity.ProjectStatus
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class ProjectEnumParser {

    fun parseRole(role: String): ProjectMemberRole =
        try {
            ProjectMemberRole.valueOf(role.uppercase())
        } catch (e: IllegalArgumentException) {
            throw ExpectedException("유효하지 않은 역할입니다: $role", HttpStatus.BAD_REQUEST)
        }

    fun parseStatus(status: String): ProjectStatus =
        try {
            ProjectStatus.valueOf(status.uppercase())
        } catch (e: IllegalArgumentException) {
            throw ExpectedException("유효하지 않은 상태입니다: $status", HttpStatus.BAD_REQUEST)
        }
}
