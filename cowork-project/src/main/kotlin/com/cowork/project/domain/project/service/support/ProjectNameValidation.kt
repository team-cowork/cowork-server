package com.cowork.project.domain.project.service.support

import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

private const val MAX_PROJECT_NAME_LENGTH = 100

fun requireValidProjectName(name: String) {
    if (name.isBlank()) throw ExpectedException("프로젝트 이름은 공백일 수 없습니다.", HttpStatus.BAD_REQUEST)
    if (name.codePointCount(0, name.length) > MAX_PROJECT_NAME_LENGTH) {
        throw ExpectedException("프로젝트 이름은 100자를 초과할 수 없습니다.", HttpStatus.BAD_REQUEST)
    }
}
