package com.cowork.channel.domain.thread.entity

enum class ThreadStatus {
    /** cowork-chat이 시작 메시지를 검증하기 전이다. */
    PENDING,

    /** 시작 메시지가 확인되어 메시지를 주고받을 수 있다. */
    ACTIVE,

    /** 시작 메시지가 없거나 스레드 메시지여서 거부되었다. 거부된 스레드는 지우고 상태 원장에만 남긴다. */
    REJECTED,
}
