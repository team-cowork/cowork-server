package com.cowork.project.global.consumer

import com.cowork.project.domain.githubBoard.event.GITHUB_PROJECT_ITEM_WRITE_RESULT_TOPIC
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandType
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteResult
import com.cowork.project.domain.githubBoard.service.GithubProjectItemWriteResultHandler
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
class GithubProjectItemWriteResultConsumer(
    private val handler: GithubProjectItemWriteResultHandler,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(
        topics = [GITHUB_PROJECT_ITEM_WRITE_RESULT_TOPIC],
        groupId = "cowork-project.github-project-item-write-result",
        containerFactory = "githubProjectItemWriteResultListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val result = runCatching {
            requireNotNull(objectMapper.readValue(record.value(), GithubProjectItemWriteResult::class.java))
        }.getOrElse { throw IllegalArgumentException("GitHub Project 카드 쓰기 result JSON이 유효하지 않습니다.", it) }
        validate(result)
        handler.apply(result)
    }

    internal fun validate(result: GithubProjectItemWriteResult) {
        require(result.schemaVersion == 1) { "지원하지 않는 schemaVersion입니다." }
        require(runCatching { UUID.fromString(result.operationId) }.isSuccess) { "operationId가 UUID가 아닙니다." }
        require(result.idempotencyKey.isNotBlank() && result.idempotencyKey.length <= 200) {
            "idempotencyKey가 유효하지 않습니다."
        }
        require(GithubProjectItemWriteCommandType.entries.any { it.name == result.commandType }) {
            "지원하지 않는 commandType입니다: ${result.commandType}"
        }
        when (result.status) {
            "SUCCEEDED" -> {
                require(result.error == null) { "SUCCEEDED에는 error를 포함할 수 없습니다." }
                val itemId = result.result?.get("itemId")
                if (itemId != null || result.commandType in setOf("CREATE_DRAFT", "ADD_ITEM")) {
                    require(itemId is String && itemId.isNotBlank() && itemId.length <= 100) {
                        "result.itemId가 유효하지 않습니다."
                    }
                }
            }

            "FAILED" -> {
                val error = requireNotNull(result.error) { "FAILED에는 error가 필요합니다." }
                require(error.code.isNotBlank() && error.code.length <= 100) { "error.code가 유효하지 않습니다." }
                require(error.message.isNotBlank() && error.message.length <= 500) { "error.message가 유효하지 않습니다." }
            }

            else -> throw IllegalArgumentException("지원하지 않는 result status입니다.")
        }
    }
}
