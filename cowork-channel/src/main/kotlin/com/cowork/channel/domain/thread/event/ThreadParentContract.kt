package com.cowork.channel.domain.thread.event

/** 스레드 시작 메시지 검증을 cowork-chat에 맡기는 command/result 계약. */
object ThreadParentContract {
    const val COMMAND_TOPIC = "chat.thread-parent.command.v1"
    const val RESULT_TOPIC = "chat.thread-parent.result.v1"
    const val RESULT_DLT_TOPIC = "chat.thread-parent.result.v1-dlt"
    const val SCHEMA_VERSION = 1
}

data class ThreadParentCommand(
    val schemaVersion: Int = ThreadParentContract.SCHEMA_VERSION,
    val threadId: Long,
    val channelId: Long,
    val parentMessageId: String,
)

data class ThreadParentResult(
    val schemaVersion: Int,
    val threadId: Long,
    val channelId: Long,
    val parentMessageId: String,
    val accepted: Boolean,
    val reason: String? = null,
)
