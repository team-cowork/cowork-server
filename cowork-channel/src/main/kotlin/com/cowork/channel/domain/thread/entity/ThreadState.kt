package com.cowork.channel.domain.thread.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** `channel.thread.state.v1`로 발행한 마지막 스레드 상태. 스레드가 지워진 뒤에도 삭제 이력을 snapshot에 남긴다. */
@Entity
@Table(name = "tb_thread_states")
class ThreadState(
    @Id
    @Column(name = "thread_id")
    val threadId: Long,
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,
    @Column(nullable = false, length = 100)
    var name: String,
    @Column(name = "parent_message_id", nullable = false, length = 24)
    val parentMessageId: String,
    @Column(name = "created_by", nullable = false)
    val createdBy: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ThreadStatus,
    @Column(nullable = false)
    var archived: Boolean = false,
    @Column(nullable = false)
    var deleted: Boolean = false,
    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant,
)
