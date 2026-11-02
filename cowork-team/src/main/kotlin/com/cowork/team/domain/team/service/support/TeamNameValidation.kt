package com.cowork.team.domain.team.service.support

import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

private const val MAX_TEAM_NAME_LENGTH = 100

fun requireValidTeamName(name: String) {
    if (name.isBlank()) throw ExpectedException("팀 이름은 공백일 수 없습니다.", HttpStatus.BAD_REQUEST)
    if (name.length > MAX_TEAM_NAME_LENGTH) {
        throw ExpectedException("팀 이름은 100자를 초과할 수 없습니다.", HttpStatus.BAD_REQUEST)
    }
}
