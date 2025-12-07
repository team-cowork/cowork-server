package com.cowork.notification.push

import com.cowork.notification.domain.NotificationMessage

interface PushSender {

    /**
     * 푸시를 발송하고, 더 이상 유효하지 않은 토큰 목록을 반환한다.
     */
    fun send(tokens: List<String>, message: NotificationMessage): List<String>
}
