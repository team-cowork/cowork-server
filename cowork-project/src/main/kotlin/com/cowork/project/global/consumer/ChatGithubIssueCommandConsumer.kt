package com.cowork.project.global.consumer

import com.cowork.project.domain.github.event.CHAT_GITHUB_ISSUE_COMMAND_TOPIC
import com.cowork.project.domain.github.event.ChatGithubIssueCreateCommand
import com.cowork.project.domain.github.service.ChatGithubIssueCommandProcessor
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ChatGithubIssueCommandConsumer(
    private val processor: ChatGithubIssueCommandProcessor,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(
        topics = [CHAT_GITHUB_ISSUE_COMMAND_TOPIC],
        groupId = "cowork-project.chat-github-issue-command",
        containerFactory = "chatGithubIssueCommandListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val command = runCatching {
            requireNotNull(objectMapper.readValue(record.value(), ChatGithubIssueCreateCommand::class.java))
        }.getOrElse { throw IllegalArgumentException("채팅발 GitHub 이슈 생성 command JSON이 유효하지 않습니다.", it) }
        validate(record.key(), command)
        processor.process(command)
    }

    internal fun validate(messageKey: String?, command: ChatGithubIssueCreateCommand) {
        require(command.schemaVersion == 1) { "지원하지 않는 schemaVersion입니다." }
        require(runCatching { UUID.fromString(command.operationId) }.isSuccess) { "operationId가 UUID가 아닙니다." }
        require(runCatching { UUID.fromString(command.idempotencyKey) }.isSuccess) { "idempotencyKey가 UUID가 아닙니다." }
        require(messageKey == command.channelId.toString()) { "channelId와 Kafka key가 일치하지 않습니다." }
        require(command.projectId > 0) { "projectId는 양수여야 합니다." }
        require(command.channelId > 0) { "channelId는 양수여야 합니다." }
        require(command.teamId > 0) { "teamId는 양수여야 합니다." }
        require(command.requesterId > 0) { "requesterId는 양수여야 합니다." }
        require(command.title.isNotBlank()) { "title이 유효하지 않습니다." }
    }
}
