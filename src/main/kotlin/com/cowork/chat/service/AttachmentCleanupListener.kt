package com.cowork.chat.service

import com.cowork.global.storage.StorageService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

data class AttachmentsOrphanedEvent(val objectKeys: List<String>)

/**
 * 채널 삭제가 커밋된 뒤 저장소에 남은 첨부파일을 지운다. 파일이 많아도 채널 삭제 요청이 느려지지 않게 비동기로 처리한다.
 */
@Component
class AttachmentCleanupListener(
    private val storageService: StorageService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener
    fun onOrphaned(event: AttachmentsOrphanedEvent) {
        event.objectKeys.forEach { key ->
            runCatching { storageService.delete(key) }
                .onFailure { log.warn("Fail to delete chat attachment {}", key, it) }
        }
    }
}
