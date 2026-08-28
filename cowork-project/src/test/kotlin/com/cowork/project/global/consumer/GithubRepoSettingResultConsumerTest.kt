package com.cowork.project.global.consumer

import com.cowork.project.domain.githubPreference.event.GithubRepoSettingError
import com.cowork.project.domain.githubPreference.event.GithubRepoSettingResult
import com.cowork.project.domain.githubPreference.event.GithubRepoSettingValue
import com.cowork.project.domain.githubPreference.service.GithubRepoSettingResultHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class GithubRepoSettingResultConsumerTest {

    private val handler = mockk<GithubRepoSettingResultHandler>(relaxed = true)
    private val objectMapper = mockk<ObjectMapper>()
    private val consumer = GithubRepoSettingResultConsumer(handler, objectMapper)

    private val operationId = "12b91f08-a7b9-4c6d-b26a-23f717e72e1c"

    private fun succeeded() = GithubRepoSettingResult(
        schemaVersion = 1,
        operationId = operationId,
        idempotencyKey = "request-1",
        repoId = 5L,
        status = "SUCCEEDED",
        settings = GithubRepoSettingValue(labelAutoApply = false),
        stateOccurredAt = Instant.parse("2026-08-27T00:00:00.123456Z"),
        occurredAt = Instant.parse("2026-08-27T00:00:00Z"),
    )

    @Test
    fun `유효한 SUCCEEDED result는 검증 후 handler에 위임한다`() {
        val payload = succeeded()
        every { objectMapper.readValue(any<String>(), GithubRepoSettingResult::class.java) } returns payload

        consumer.consume(ConsumerRecord("preference.github-repo.setting.result", 0, 1L, operationId, "{}"))

        verify(exactly = 1) { handler.apply(payload) }
    }

    @Test
    fun `유효한 FAILED result도 handler에 위임한다`() {
        val payload = GithubRepoSettingResult(
            schemaVersion = 1,
            operationId = operationId,
            idempotencyKey = "request-1",
            repoId = 5L,
            status = "FAILED",
            error = GithubRepoSettingError("INVALID_SETTING", "label_auto_apply is invalid"),
            occurredAt = Instant.parse("2026-08-27T00:00:00Z"),
        )
        every { objectMapper.readValue(any<String>(), GithubRepoSettingResult::class.java) } returns payload

        consumer.consume(ConsumerRecord("preference.github-repo.setting.result", 0, 1L, operationId, "{}"))

        verify(exactly = 1) { handler.apply(payload) }
    }

    @Test
    fun `JSON 역직렬화 실패는 IllegalArgumentException으로 감싼다`() {
        every {
            objectMapper.readValue(any<String>(), GithubRepoSettingResult::class.java)
        } throws RuntimeException("broken json")

        assertThrows(IllegalArgumentException::class.java) {
            consumer.consume(ConsumerRecord("preference.github-repo.setting.result", 0, 1L, operationId, "not-json"))
        }

        verify(exactly = 0) { handler.apply(any()) }
    }

    @Test
    fun `validate는 지원하지 않는 schemaVersion을 거부한다`() {
        val payload = succeeded().copy(schemaVersion = 2)

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate(operationId, payload)
        }
    }

    @Test
    fun `validate는 operationId가 UUID가 아니면 거부한다`() {
        val payload = succeeded().copy(operationId = "not-a-uuid")

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate("not-a-uuid", payload)
        }
    }

    @Test
    fun `validate는 Kafka key와 operationId가 다르면 거부한다`() {
        val payload = succeeded()

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate("different-key", payload)
        }
    }

    @Test
    fun `validate는 idempotencyKey가 비어있으면 거부한다`() {
        val payload = succeeded().copy(idempotencyKey = "")

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate(operationId, payload)
        }
    }

    @Test
    fun `validate는 repoId가 0 이하이면 거부한다`() {
        val payload = succeeded().copy(repoId = 0L)

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate(operationId, payload)
        }
    }

    @Test
    fun `validate는 occurredAt이 없으면 거부한다`() {
        val payload = succeeded().copy(occurredAt = null)

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate(operationId, payload)
        }
    }

    @Test
    fun `validate는 SUCCEEDED에 settings가 없으면 거부한다`() {
        val payload = succeeded().copy(settings = null)

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate(operationId, payload)
        }
    }

    @Test
    fun `validate는 SUCCEEDED에 error가 포함되면 거부한다`() {
        val payload = succeeded().copy(error = GithubRepoSettingError("CODE", "message"))

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate(operationId, payload)
        }
    }

    @Test
    fun `validate는 SUCCEEDED에 stateOccurredAt이 없으면 거부한다`() {
        val payload = succeeded().copy(stateOccurredAt = null)

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate(operationId, payload)
        }
    }

    @Test
    fun `validate는 FAILED에 settings가 포함되면 거부한다`() {
        val payload = GithubRepoSettingResult(
            schemaVersion = 1,
            operationId = operationId,
            idempotencyKey = "request-1",
            repoId = 5L,
            status = "FAILED",
            settings = GithubRepoSettingValue(labelAutoApply = false),
            error = GithubRepoSettingError("CODE", "message"),
            occurredAt = Instant.parse("2026-08-27T00:00:00Z"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate(operationId, payload)
        }
    }

    @Test
    fun `validate는 FAILED에 stateOccurredAt이 포함되면 거부한다`() {
        val payload = GithubRepoSettingResult(
            schemaVersion = 1,
            operationId = operationId,
            idempotencyKey = "request-1",
            repoId = 5L,
            status = "FAILED",
            error = GithubRepoSettingError("CODE", "message"),
            stateOccurredAt = Instant.parse("2026-08-27T00:00:00Z"),
            occurredAt = Instant.parse("2026-08-27T00:00:00Z"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate(operationId, payload)
        }
    }

    @Test
    fun `validate는 FAILED에 error가 없으면 거부한다`() {
        val payload = GithubRepoSettingResult(
            schemaVersion = 1,
            operationId = operationId,
            idempotencyKey = "request-1",
            repoId = 5L,
            status = "FAILED",
            error = null,
            occurredAt = Instant.parse("2026-08-27T00:00:00Z"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate(operationId, payload)
        }
    }

    @Test
    fun `validate는 지원하지 않는 status를 거부한다`() {
        val payload = succeeded().copy(status = "UNKNOWN")

        assertThrows(IllegalArgumentException::class.java) {
            consumer.validate(operationId, payload)
        }
    }
}
