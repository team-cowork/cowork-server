package com.cowork.project.global.consumer

import com.cowork.project.domain.githubPreference.event.GithubRepoSettingState
import com.cowork.project.domain.githubPreference.event.GithubRepoSettingValue
import com.cowork.project.domain.githubPreference.service.GithubRepoPreferenceProjectionHandler
import com.cowork.project.global.projection.ProjectionRecordProcessor
import com.cowork.project.global.projection.ProjectionStreams
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import java.time.Instant

class GithubRepoSettingStateConsumerTest {

    private val handler = mockk<GithubRepoPreferenceProjectionHandler>(relaxed = true)
    private val objectMapper = mockk<ObjectMapper>()
    private val processor = mockk<ProjectionRecordProcessor>(relaxed = true)
    private val streams = ProjectionStreams()
    private val consumer = GithubRepoSettingStateConsumer(handler, objectMapper, processor, streams)

    @Test
    fun `control record는 processControlRecord에서 소비되고 이후 로직을 타지 않는다`() {
        every { processor.processControlRecord(streams.githubRepoSetting, any()) } returns true
        val record = ConsumerRecord("preference.github-repo.setting.state", 0, 1L, "__snapshot__:0", "{}")

        consumer.consume(record)

        verify(exactly = 0) { objectMapper.readValue(any<String>(), GithubRepoSettingState::class.java) }
        verify(exactly = 0) { handler.apply(any(), any(), any(), any()) }
    }

    @Test
    fun `key와 필드가 유효한 UPSERT 이벤트만 반영한다`() {
        every { processor.processControlRecord(streams.githubRepoSetting, any()) } returns false
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        val payload = GithubRepoSettingState(
            schemaVersion = 1,
            eventType = "UPSERT",
            repoId = 5L,
            settings = GithubRepoSettingValue(labelAutoApply = false),
            occurredAt = occurredAt,
        )
        every { objectMapper.readValue(any<String>(), GithubRepoSettingState::class.java) } returns payload
        every { processor.applyRecord(any(), any(), any()) } answers { thirdArg<() -> Unit>().invoke() }

        consumer.consume(ConsumerRecord("preference.github-repo.setting.state", 0, 1L, "5", "{}"))

        verify(exactly = 1) { handler.apply(5L, false, false, occurredAt) }
    }

    @Test
    fun `DELETE 이벤트는 labelAutoApply 기본값 true로 deleted true를 반영한다`() {
        every { processor.processControlRecord(streams.githubRepoSetting, any()) } returns false
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        val payload = GithubRepoSettingState(
            schemaVersion = 1,
            eventType = "DELETE",
            repoId = 5L,
            settings = null,
            occurredAt = occurredAt,
        )
        every { objectMapper.readValue(any<String>(), GithubRepoSettingState::class.java) } returns payload
        every { processor.applyRecord(any(), any(), any()) } answers { thirdArg<() -> Unit>().invoke() }

        consumer.consume(ConsumerRecord("preference.github-repo.setting.state", 0, 1L, "5", "{}"))

        verify(exactly = 1) { handler.apply(5L, true, true, occurredAt) }
    }

    @Test
    fun `지원하지 않는 schemaVersion은 격리한다`() {
        every { processor.processControlRecord(streams.githubRepoSetting, any()) } returns false
        val payload = GithubRepoSettingState(
            schemaVersion = 2,
            eventType = "UPSERT",
            repoId = 5L,
            settings = GithubRepoSettingValue(labelAutoApply = false),
            occurredAt = Instant.parse("2026-08-26T03:00:00Z"),
        )
        every { objectMapper.readValue(any<String>(), GithubRepoSettingState::class.java) } returns payload

        consumer.consume(ConsumerRecord("preference.github-repo.setting.state", 0, 1L, "5", "{}"))

        verify(exactly = 1) { processor.quarantineRecord(streams.githubRepoSetting, any(), any()) }
        verify(exactly = 0) { handler.apply(any(), any(), any(), any()) }
    }

    @Test
    fun `repoId와 Kafka key가 다르면 격리한다`() {
        every { processor.processControlRecord(streams.githubRepoSetting, any()) } returns false
        val payload = GithubRepoSettingState(
            schemaVersion = 1,
            eventType = "UPSERT",
            repoId = 5L,
            settings = GithubRepoSettingValue(labelAutoApply = false),
            occurredAt = Instant.parse("2026-08-26T03:00:00Z"),
        )
        every { objectMapper.readValue(any<String>(), GithubRepoSettingState::class.java) } returns payload

        consumer.consume(ConsumerRecord("preference.github-repo.setting.state", 0, 1L, "999", "{}"))

        verify(exactly = 1) { processor.quarantineRecord(streams.githubRepoSetting, any(), any()) }
        verify(exactly = 0) { handler.apply(any(), any(), any(), any()) }
    }

    @Test
    fun `UPSERT인데 settings가 없으면 격리한다`() {
        every { processor.processControlRecord(streams.githubRepoSetting, any()) } returns false
        val payload = GithubRepoSettingState(
            schemaVersion = 1,
            eventType = "UPSERT",
            repoId = 5L,
            settings = null,
            occurredAt = Instant.parse("2026-08-26T03:00:00Z"),
        )
        every { objectMapper.readValue(any<String>(), GithubRepoSettingState::class.java) } returns payload

        consumer.consume(ConsumerRecord("preference.github-repo.setting.state", 0, 1L, "5", "{}"))

        verify(exactly = 1) { processor.quarantineRecord(streams.githubRepoSetting, any(), any()) }
        verify(exactly = 0) { handler.apply(any(), any(), any(), any()) }
    }

    @Test
    fun `DELETE인데 settings가 포함되어 있으면 격리한다`() {
        every { processor.processControlRecord(streams.githubRepoSetting, any()) } returns false
        val payload = GithubRepoSettingState(
            schemaVersion = 1,
            eventType = "DELETE",
            repoId = 5L,
            settings = GithubRepoSettingValue(labelAutoApply = false),
            occurredAt = Instant.parse("2026-08-26T03:00:00Z"),
        )
        every { objectMapper.readValue(any<String>(), GithubRepoSettingState::class.java) } returns payload

        consumer.consume(ConsumerRecord("preference.github-repo.setting.state", 0, 1L, "5", "{}"))

        verify(exactly = 1) { processor.quarantineRecord(streams.githubRepoSetting, any(), any()) }
        verify(exactly = 0) { handler.apply(any(), any(), any(), any()) }
    }

    @Test
    fun `JSON 역직렬화에 실패하면 격리한다`() {
        every { processor.processControlRecord(streams.githubRepoSetting, any()) } returns false
        every {
            objectMapper.readValue(any<String>(), GithubRepoSettingState::class.java)
        } throws RuntimeException("broken json")

        consumer.consume(ConsumerRecord("preference.github-repo.setting.state", 0, 1L, "5", "not-json"))

        verify(exactly = 1) { processor.quarantineRecord(streams.githubRepoSetting, any(), any()) }
    }

    @Test
    fun `occurredAt이 없으면 격리한다`() {
        every { processor.processControlRecord(streams.githubRepoSetting, any()) } returns false
        val payload = GithubRepoSettingState(
            schemaVersion = 1,
            eventType = "UPSERT",
            repoId = 5L,
            settings = GithubRepoSettingValue(labelAutoApply = false),
            occurredAt = null,
        )
        every { objectMapper.readValue(any<String>(), GithubRepoSettingState::class.java) } returns payload

        consumer.consume(ConsumerRecord("preference.github-repo.setting.state", 0, 1L, "5", "{}"))

        verify(exactly = 1) { processor.quarantineRecord(streams.githubRepoSetting, any(), any()) }
    }
}
