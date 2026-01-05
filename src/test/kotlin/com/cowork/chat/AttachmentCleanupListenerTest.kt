package com.cowork.chat

import com.cowork.chat.service.AttachmentCleanupListener
import com.cowork.chat.service.AttachmentsOrphanedEvent
import com.cowork.global.storage.StorageService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class AttachmentCleanupListenerTest {

    private val storageService = mockk<StorageService>(relaxed = true)
    private val listener = AttachmentCleanupListener(storageService)

    @Test
    fun `일부 파일 삭제에 실패해도 나머지 파일을 지운다`() {
        every { storageService.delete("a") } throws IllegalStateException("S3 down")

        listener.onOrphaned(AttachmentsOrphanedEvent(listOf("a", "b")))

        verify { storageService.delete("b") }
    }
}
