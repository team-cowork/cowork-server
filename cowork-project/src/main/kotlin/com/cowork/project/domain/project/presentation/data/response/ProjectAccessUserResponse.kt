package com.cowork.project.domain.project.presentation.data.response

/** 프로젝트 read가 허용된 사용자와 독립적인 write 권한. */
data class ProjectAccessUserResponse(val projectId: Long, val userId: Long, val write: Boolean)
