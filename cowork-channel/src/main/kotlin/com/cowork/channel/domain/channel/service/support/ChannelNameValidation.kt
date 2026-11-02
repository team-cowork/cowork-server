package com.cowork.channel.domain.channel.service.support

import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

private const val MAX_CHANNEL_NAME_LENGTH = 100

fun requireValidChannelName(name: String) {
    if (name.isBlank()) throw ExpectedException("채널 이름은 공백일 수 없습니다.", HttpStatus.BAD_REQUEST)
    if (name.length > MAX_CHANNEL_NAME_LENGTH) {
        throw ExpectedException("채널 이름은 100자를 초과할 수 없습니다.", HttpStatus.BAD_REQUEST)
    }
}
