package com.cowork.preference.messaging

import com.cowork.preference.repository.GithubRepoSettingCommandInboxRepository
import com.cowork.preference.service.GithubRepoSettingCommandProcessor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kafka.client.consumer.KafkaConsumerRecord
import io.vertx.kafka.client.consumer.KafkaConsumerRecords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * GithubRepoSettingCommandConsumer는 실제 Kafka 브로커 연결 없이는 start()/close()를 통해 전체 배치 처리
 * 경로를 검증할 수 없으므로(KafkaConsumer.create가 즉시 실제 Vert.x Kafka 클라이언트 객체를 생성함), 순수
 * 로직인 private process()를 리플렉션으로 직접 호출해 검증한다. process()는 vertx/scope에 의존하지 않고
 * processor/inboxRepository/closed 플래그에만 의존한다.
 *
 * KafkaConsumer.create(vertx, ...)는 실제 Vert.x 내부 컨텍스트 API를 사용하므로 vertx는 mock이 아닌
 * 실경량 Vertx.vertx() 인스턴스를 사용한다(브로커 연결은 subscribe() 호출 시 비로소 발생하며, 이 테스트는
 * subscribe()를 트리거하는 start()를 호출하지 않으므로 네트워크 I/O가 발생하지 않는다).
 */
class GithubRepoSettingCommandConsumerTest {

    private val vertx: Vertx = Vertx.vertx()

    @AfterEach
    fun tearDown() {
        vertx.close()
    }

    private fun consumerWith(
        processor: GithubRepoSettingCommandProcessor,
        inboxRepository: GithubRepoSettingCommandInboxRepository,
    ): GithubRepoSettingCommandConsumer {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        return GithubRepoSettingCommandConsumer(
            vertx = vertx,
            bootstrapServers = "localhost:9092",
            groupId = "test-group",
            processor = processor,
            inboxRepository = inboxRepository,
            scope = scope,
        )
    }

    private fun record(key: String?, value: String?, offset: Long = 0L): KafkaConsumerRecord<String, String> {
        val record = mockk<KafkaConsumerRecord<String, String>>()
        every { record.key() } returns key
        every { record.value() } returns value
        every { record.topic() } returns PreferenceEvents.GITHUB_REPO_SETTING_COMMAND_TOPIC
        every { record.partition() } returns 0
        every { record.offset() } returns offset
        return record
    }

    private fun batchOf(vararg records: KafkaConsumerRecord<String, String>): KafkaConsumerRecords<String, String> {
        val batch = mockk<KafkaConsumerRecords<String, String>>()
        every { batch.size() } returns records.size
        records.forEachIndexed { index, item -> every { batch.recordAt(index) } returns item }
        return batch
    }

    private suspend fun invokeProcess(
        consumer: GithubRepoSettingCommandConsumer,
        records: KafkaConsumerRecords<String, String>,
    ) {
        val function = GithubRepoSettingCommandConsumer::class.declaredFunctions
            .single { it.name == "process" }
        function.isAccessible = true
        function.callSuspend(consumer, records)
    }

    @Test
    fun `Apply로 파싱된 레코드는 processor process를 호출한다`() = runBlocking {
        val processor = mockk<GithubRepoSettingCommandProcessor>()
        val inboxRepository = mockk<GithubRepoSettingCommandInboxRepository>()
        val payload = JsonObject()
            .put("schemaVersion", 1)
            .put("operationId", "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e")
            .put("idempotencyKey", "github-policy-1")
            .put("commandType", "UPDATE")
            .put("repoId", 101)
            .put("requestedBy", 7)
            .put("occurredAt", "2026-08-27T01:01:00Z")
            .put("settings", JsonObject().put("label_auto_apply", true))
        val commandSlot = slot<GithubRepoSettingCommand>()
        coEvery { processor.process(capture(commandSlot)) } returns Unit
        val consumer = consumerWith(processor, inboxRepository)

        invokeProcess(consumer, batchOf(record("101", payload.encode())))

        coVerify(exactly = 1) { processor.process(any()) }
        assertEquals(101L, commandSlot.captured.repoId)
        assertEquals(true, commandSlot.captured.labelAutoApply)
    }

    @Test
    fun `Reject로 파싱된 레코드는 processor rejectInvalid를 envelope와 사유로 호출한다`() = runBlocking {
        val processor = mockk<GithubRepoSettingCommandProcessor>()
        val inboxRepository = mockk<GithubRepoSettingCommandInboxRepository>()
        val payload = JsonObject()
            .put("schemaVersion", 1)
            .put("operationId", "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e")
            .put("idempotencyKey", "github-policy-1")
            .put("commandType", "UPDATE")
            .put("repoId", 101)
            .put("requestedBy", 7)
            .put("occurredAt", "2026-08-27T01:01:00Z")
            .put("settings", JsonObject().put("label_auto_apply", "invalid"))
        coEvery { processor.rejectInvalid(any(), any(), any()) } returns Unit
        val consumer = consumerWith(processor, inboxRepository)

        invokeProcess(consumer, batchOf(record("101", payload.encode(), offset = 5L)))

        val envelopeSlot = slot<GithubRepoSettingCommandEnvelope>()
        val quarantineSlot = slot<GithubRepoSettingCommandQuarantineRecord>()
        val reasonSlot = slot<String>()
        coVerify(exactly = 1) {
            processor.rejectInvalid(capture(envelopeSlot), capture(quarantineSlot), capture(reasonSlot))
        }
        assertEquals(101L, envelopeSlot.captured.repoId)
        assertEquals(5L, quarantineSlot.captured.offset)
        assertEquals(PreferenceEvents.GITHUB_REPO_SETTING_COMMAND_TOPIC, quarantineSlot.captured.topic)
        assertEquals("label_auto_apply must be boolean", reasonSlot.captured)
    }

    @Test
    fun `역직렬화에 실패한 garbage 레코드는 inboxRepository quarantine으로 격리한다`() = runBlocking {
        val processor = mockk<GithubRepoSettingCommandProcessor>()
        val inboxRepository = mockk<GithubRepoSettingCommandInboxRepository>()
        coEvery { inboxRepository.quarantine(any<GithubRepoSettingCommandQuarantineRecord>()) } returns true
        val consumer = consumerWith(processor, inboxRepository)

        invokeProcess(consumer, batchOf(record("101", "not-json", offset = 9L)))

        val quarantineSlot = slot<GithubRepoSettingCommandQuarantineRecord>()
        coVerify(exactly = 1) { inboxRepository.quarantine(capture(quarantineSlot)) }
        assertEquals(9L, quarantineSlot.captured.offset)
        assertEquals("not-json", quarantineSlot.captured.payload)
        coVerify(exactly = 0) { processor.process(any()) }
        coVerify(exactly = 0) { processor.rejectInvalid(any(), any(), any()) }
    }

    @Test
    fun `한 배치에 여러 레코드가 있으면 순서대로 모두 처리한다`() = runBlocking {
        val processor = mockk<GithubRepoSettingCommandProcessor>()
        val inboxRepository = mockk<GithubRepoSettingCommandInboxRepository>()
        coEvery { processor.process(any()) } returns Unit
        coEvery { inboxRepository.quarantine(any<GithubRepoSettingCommandQuarantineRecord>()) } returns true
        val applyPayload = JsonObject()
            .put("schemaVersion", 1)
            .put("operationId", "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e")
            .put("idempotencyKey", "github-policy-1")
            .put("commandType", "DELETE")
            .put("repoId", 202)
            .put("occurredAt", "2026-08-27T01:01:00Z")
        val consumer = consumerWith(processor, inboxRepository)

        invokeProcess(
            consumer,
            batchOf(
                record("202", applyPayload.encode(), offset = 1L),
                record("bad", "garbage", offset = 2L),
            ),
        )

        coVerify(exactly = 1) { processor.process(any()) }
        coVerify(exactly = 1) { inboxRepository.quarantine(any<GithubRepoSettingCommandQuarantineRecord>()) }
    }
}
