package com.cowork.preference.service

import com.cowork.preference.domain.ChannelLifecycleProjection
import com.cowork.preference.domain.Webhook
import com.cowork.preference.domain.WebhookRequestException
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.WebhookOperation
import com.cowork.preference.repository.WebhookRepository
import com.cowork.preference.security.RequesterContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID

class WebhookServiceTest {
    private val pool = mockk<Pool>()
    private val repository = mockk<WebhookRepository>(relaxed = true)
    private val channels = mockk<ChannelLifecycleProjectionRepository>()
    private val guard = mockk<ChannelAccessGuard>(relaxed = true)
    private val outbox = mockk<PreferenceOutboxRepository>(relaxed = true)
    private val readiness = mockk<ProjectionReadinessView>()
    private val connection = mockk<SqlConnection>()
    private val secrets = WebhookSecrets(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
    private val requester = RequesterContext(3, "MEMBER")
    private val now = Instant.parse("2026-10-25T10:00:00Z")
    private val executionId = UUID.fromString("5b30857f-0bfa-48b5-ac0b-5c64e28078d1")
    private val nextExecutionId = UUID.fromString("6cf6eda2-99a4-4f45-9bc9-14292c607df0")
    private val original = Webhook(7, 11, "bot", null, false, null, true, false, 3, now, now, null, executionId)
    private lateinit var service: WebhookService
    private var committed = false

    @BeforeEach
    fun setup() {
        every { readiness.isReady } returns true
        coEvery { outbox.inTransaction<JsonObject>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> JsonObject>().invoke(connection).also { committed = true }
        }
        coEvery { channels.findForUpdate(connection, 11) } returns ChannelLifecycleProjection(11, 2, false, now, false, "TEXT")
        coEvery { repository.findForUpdate(connection, 7) } returns original
        coEvery { repository.findOperation(any(), any(), any()) } returns null
        coEvery { repository.now(connection) } returns now
        service = WebhookService(pool, repository, channels, guard, outbox, readiness, secrets)
    }

    @Test
    fun `보안 웹훅 생성은 hash와 암호화된 재생 응답만 저장한다`() = runBlocking {
        val event = slot<PreferenceEvent>()
        val response = slot<JsonObject>()
        val ciphertext = slot<String>()
        coEvery { repository.create(connection, 11, 3, any(), any()) } coAnswers {
            original.copy(isSecure = true, secretHash = arg(4))
        }
        coEvery { outbox.enqueue(connection, capture(event)) } returns Unit
        coEvery { repository.saveOperation(connection, 3, "create", any(), capture(response), capture(ciphertext)) } returns Unit
        val result = service.create(11, requester, JsonObject().put("name", "bot").put("isSecure", true), "create")
        val token = result.getString("token")
        assertEquals(executionId.toString(), result.getString("executionId"))
        assertEquals(executionId.toString(), event.captured.payload.getString("executionId"))
        assertEquals(32, Base64.getUrlDecoder().decode(token).size)
        assertEquals(WebhookSecrets.hash(token), event.captured.payload.getString("secretHash"))
        assertFalse(event.captured.payload.encode().contains(token))
        assertNull(response.captured.getString("token"))
        assertFalse(ciphertext.captured.contains(token))
        assertTrue(committed)
    }

    @Test
    fun `일반 수정은 null을 포함한 isSecure 존재를 거부한다`() = runBlocking {
        for (value in listOf(null, true, false)) {
            val failure = runCatching { service.patch(11, 7, requester, JsonObject().put("isSecure", value)) }.exceptionOrNull()
            assertEquals(400, (failure as WebhookRequestException).status)
        }
        coVerify(exactly = 0) { repository.patch(any(), any(), any()) }
    }

    @Test
    fun `최초 전환은 즉시 허용하고 새 secret을 한 번 발급한다`() = runBlocking {
        coEvery { repository.changeMode(connection, original, true, any(), now) } coAnswers {
            original.copy(isSecure = true, secretHash = arg(3), modeChangedAt = now)
        }
        val result = service.changeMode(11, 7, requester, JsonObject().put("isSecure", true), "first")
        assertNotNull(result.getString("token"))
        assertEquals(executionId.toString(), result.getString("executionId"))
        coVerify(exactly = 1) { repository.changeMode(connection, original, true, any(), now) }
        coVerify { guard.requireSettingsManager(connection, 11, requester) }
    }

    @Test
    fun `같은 모드는 cooldown 중에도 rotation과 timestamp 갱신 없이 성공한다`() = runBlocking {
        val current = original.copy(isSecure = true, secretHash = "a".repeat(64), modeChangedAt = now.minusSeconds(1))
        coEvery { repository.findForUpdate(connection, 7) } returns current
        val result = service.changeMode(11, 7, requester, JsonObject().put("isSecure", true), "noop")
        assertTrue(result.getBoolean("isSecure"))
        assertNull(result.getString("token"))
        coVerify(exactly = 0) { repository.changeMode(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { outbox.enqueue(any(), any()) }
        coVerify(exactly = 0) { repository.now(any()) }
    }

    @Test
    fun `30초 미만 전환 거절은 commit되고 같은 key는 시간이 지나도 거절을 재생한다`() = runBlocking {
        val current = original.copy(modeChangedAt = now.minusSeconds(10))
        val saved = slot<JsonObject>()
        val requestHash = slot<String>()
        coEvery { repository.findForUpdate(connection, 7) } returns current
        coEvery { repository.saveOperation(connection, 3, "cooldown", capture(requestHash), capture(saved), null) } returns Unit
        val failure = runCatching { service.changeMode(11, 7, requester, JsonObject().put("isSecure", true), "cooldown") }.exceptionOrNull() as WebhookRequestException
        assertEquals(429, failure.status)
        assertEquals(now.plusSeconds(20), failure.nextAllowedAt)
        assertTrue(committed)
        coEvery { repository.findOperation(connection, 3, "cooldown") } returns WebhookOperation(requestHash.captured, saved.captured, null, now)
        coEvery { repository.now(connection) } returns now.plusSeconds(90)
        val replay = runCatching { service.changeMode(11, 7, requester, JsonObject().put("isSecure", true), "cooldown") }.exceptionOrNull() as WebhookRequestException
        assertEquals(429, replay.status)
        assertEquals(failure.nextAllowedAt, replay.nextAllowedAt)
        coVerify(exactly = 0) { repository.changeMode(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `30초 경계 직전의 짧은 잔여 시간도 전환을 허용하지 않는다`() = runBlocking {
        coEvery { repository.findForUpdate(connection, 7) } returns original.copy(modeChangedAt = now.minusSeconds(30).plusNanos(1))
        val failure = runCatching { service.changeMode(11, 7, requester, JsonObject().put("isSecure", true), "boundary") }.exceptionOrNull()
        assertEquals(429, (failure as WebhookRequestException).status)
        coVerify(exactly = 0) { repository.changeMode(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `정확히 30초가 지나면 전환하고 다음 cooldown을 시작한다`() = runBlocking {
        val current = original.copy(modeChangedAt = now.minusSeconds(30))
        coEvery { repository.findForUpdate(connection, 7) } returns current
        coEvery { repository.changeMode(connection, current, true, any(), now) } coAnswers {
            current.copy(isSecure = true, secretHash = arg(3), modeChangedAt = now)
        }
        service.changeMode(11, 7, requester, JsonObject().put("isSecure", true), "allowed")
        coVerify { repository.changeMode(connection, current, true, any(), now) }
    }

    @Test
    fun `발급 후 24시간 직전까지 전환 재생은 같은 비밀키를 반환하고 다시 변경하지 않는다`() = runBlocking {
        val token = "original-secret"
        val hash = WebhookSecrets.hash("MODE:11:7:true")
        coEvery { repository.findOperation(connection, 3, "retry") } returns WebhookOperation(
            hash, original.copy(isSecure = true).response(), secrets.encrypt(token, "3:retry:$hash"),
            now.minusSeconds(24 * 60 * 60).plusNanos(1),
        )
        val result = service.changeMode(11, 7, requester, JsonObject().put("isSecure", true), "retry")
        assertEquals(token, result.getString("token"))
        assertEquals(executionId.toString(), result.getString("executionId"))
        coVerify(exactly = 0) { repository.changeMode(any(), any(), any(), any(), any()) }
        coVerify { guard.requireSettingsManager(connection, 11, requester) }
    }

    @Test
    fun `발급 후 정확히 24시간부터 보안 모드 재시도는 비밀키 없이 기존 결과만 반환한다`() = runBlocking {
        val hash = WebhookSecrets.hash("MODE:11:7:true")
        coEvery { repository.findOperation(connection, 3, "expired") } returns WebhookOperation(
            hash, original.copy(isSecure = true).response(), secrets.encrypt("expired-secret", "3:expired:$hash"),
            now.minusSeconds(24 * 60 * 60),
        )
        val result = service.changeMode(11, 7, requester, JsonObject().put("isSecure", true), "expired")
        assertNull(result.getString("token"))
        assertTrue(result.getBoolean("isSecure"))
        coVerify(exactly = 0) { repository.changeMode(any(), any(), any(), any(), any()) }
        coVerify { guard.requireSettingsManager(connection, 11, requester) }
    }

    @Test
    fun `발급 응답이 만료된 생성 요청을 반복해도 웹훅을 중복 생성하지 않는다`() = runBlocking {
        var operation: WebhookOperation? = null
        coEvery { repository.findOperation(connection, 3, "create-once") } coAnswers { operation }
        coEvery { repository.create(connection, 11, 3, any(), any()) } coAnswers {
            original.copy(isSecure = true, secretHash = arg(4))
        }
        coEvery { repository.saveOperation(connection, 3, "create-once", any(), any(), any()) } coAnswers {
            operation = WebhookOperation(arg(3), arg(4), arg(5), now)
        }
        val input = JsonObject().put("name", "bot").put("isSecure", true)
        val first = service.create(11, requester, input, "create-once")
        assertNotNull(first.getString("token"))
        coEvery { repository.now(connection) } returns now.plusSeconds(24 * 60 * 60 + 1)
        repeat(2) {
            val retry = service.create(11, requester, input, "create-once")
            assertNull(retry.getString("token"))
            assertEquals(first.getLong("id"), retry.getLong("id"))
        }
        coVerify(exactly = 1) { repository.create(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { outbox.enqueue(any(), any()) }
        coVerify(exactly = 3) { guard.requireSettingsManager(connection, 11, requester) }
    }

    @Test
    fun `실행 식별자는 생성이나 일반 수정에서 사용자가 지정할 수 없다`() = runBlocking {
        val input = JsonObject().put("executionId", executionId.toString())
        val createFailure = runCatching { service.create(11, requester, input.copy().put("name", "bot"), "chosen") }
            .exceptionOrNull() as WebhookRequestException
        val patchFailure = runCatching { service.patch(11, 7, requester, input) }
            .exceptionOrNull() as WebhookRequestException
        assertEquals(400, createFailure.status)
        assertEquals(400, patchFailure.status)
        coVerify(exactly = 0) { repository.create(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.patch(any(), any(), any()) }
    }

    @Test
    fun `이전 발급 응답 재생은 기존 실행 식별자만 보완하고 웹훅을 변경하지 않는다`() = runBlocking {
        val hash = WebhookSecrets.hash("MODE:11:7:true")
        val oldResponse = original.copy(isSecure = true).response().also { it.remove("executionId") }
        coEvery { repository.findOperation(connection, 3, "before-uuid") } returns WebhookOperation(
            hash, oldResponse, null, now.minusSeconds(24 * 60 * 60),
        )
        coEvery { repository.findForUpdate(connection, 7) } returns original.copy(name = "current name")
        val result = service.changeMode(11, 7, requester, JsonObject().put("isSecure", true), "before-uuid")
        assertEquals(executionId.toString(), result.getString("executionId"))
        assertEquals("bot", result.getString("name"))
        assertNull(result.getString("token"))
        coVerify(exactly = 0) { repository.changeMode(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.create(any(), any(), any(), any(), any()) }
        coVerify { guard.requireSettingsManager(connection, 11, requester) }
    }

    @Test
    fun `실행 ID가 폐기된 보안 모드 재시도는 이전 URL과 비밀키를 반환하지 않는다`() = runBlocking {
        val hash = WebhookSecrets.hash("MODE:11:7:true")
        coEvery { repository.findOperation(connection, 3, "obsolete") } returns WebhookOperation(
            hash, original.copy(isSecure = true).response(), secrets.encrypt("old-secret", "3:obsolete:$hash"), now,
        )
        coEvery { repository.findForUpdate(connection, 7) } returns original.copy(executionId = nextExecutionId)
        val failure = runCatching {
            service.changeMode(11, 7, requester, JsonObject().put("isSecure", true), "obsolete")
        }.exceptionOrNull()
        assertEquals(409, (failure as? WebhookRequestException)?.status)
        coVerify(exactly = 0) { repository.changeMode(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `공개 웹훅 실행 ID 재발급과 같은 키 재시도는 한 번만 회전하고 숫자 ID를 유지한다`() = runBlocking {
        var current = original
        var operation: WebhookOperation? = null
        coEvery { repository.findForUpdate(connection, 7) } coAnswers { current }
        coEvery { repository.findOperation(connection, 3, "rotate") } coAnswers { operation }
        coEvery { repository.rotateExecutionId(connection, 7) } coAnswers {
            current.copy(executionId = nextExecutionId, updatedAt = now.plusNanos(1000)).also { current = it }
        }
        coEvery { repository.saveOperation(connection, 3, "rotate", any(), any(), null) } coAnswers {
            operation = WebhookOperation(arg(3), arg(4), null, now)
        }
        val first = service.rotateExecutionId(11, 7, requester, "rotate")
        val retry = service.rotateExecutionId(11, 7, requester, "rotate")
        assertEquals(nextExecutionId.toString(), first.getString("executionId"))
        assertEquals(original.id, first.getLong("id"))
        assertEquals(original.name, first.getString("name"))
        assertFalse(first.getBoolean("isSecure"))
        assertNull(first.getString("token"))
        assertEquals(first, retry)
        coVerify(exactly = 1) { repository.rotateExecutionId(connection, 7) }
        coVerify(exactly = 1) { outbox.enqueue(connection, any()) }
    }

    @Test
    fun `보안 모드 cooldown 중에도 실행 ID만 재발급하고 비밀키와 모드 변경 시각을 유지한다`() = runBlocking {
        val current = original.copy(isSecure = true, secretHash = "a".repeat(64), modeChangedAt = now.minusSeconds(1))
        val updated = current.copy(executionId = nextExecutionId, updatedAt = now.plusNanos(1000))
        val event = slot<PreferenceEvent>()
        coEvery { repository.findForUpdate(connection, 7) } returns current
        coEvery { repository.rotateExecutionId(connection, 7) } returns updated
        coEvery { outbox.enqueue(connection, capture(event)) } returns Unit
        val result = service.rotateExecutionId(11, 7, requester, "secure-rotate")
        assertEquals(nextExecutionId.toString(), result.getString("executionId"))
        assertTrue(result.getBoolean("isSecure"))
        assertNull(result.getString("token"))
        assertEquals(current.secretHash, event.captured.payload.getString("secretHash"))
        coVerify(exactly = 0) { repository.changeMode(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { repository.rotateExecutionId(connection, 7) }
    }

    @Test
    fun `다른 재발급이나 삭제로 폐기된 재발급 키는 다시 회전하지 않고 거절한다`() = runBlocking {
        val hash = WebhookSecrets.hash("ROTATE_EXECUTION_ID:11:7")
        coEvery { repository.findOperation(connection, 3, "old-rotation") } returns WebhookOperation(
            hash, original.response(), null, now,
        )
        for (current in listOf(original.copy(executionId = nextExecutionId), original.copy(deleted = true), null)) {
            coEvery { repository.findForUpdate(connection, 7) } returns current
            val failure = runCatching { service.rotateExecutionId(11, 7, requester, "old-rotation") }.exceptionOrNull()
            assertEquals(409, (failure as? WebhookRequestException)?.status)
        }
        coVerify(exactly = 0) { repository.rotateExecutionId(any(), any()) }
        coVerify(exactly = 0) { outbox.enqueue(any(), any()) }
    }

    @Test
    fun `생성 응답의 실행 ID가 재발급 또는 삭제되면 재시도로 새 웹훅을 만들지 않는다`() = runBlocking {
        var operation: WebhookOperation? = null
        coEvery { repository.findOperation(connection, 3, "create-obsolete") } coAnswers { operation }
        coEvery { repository.create(connection, 11, 3, any(), null) } returns original
        coEvery { repository.saveOperation(connection, 3, "create-obsolete", any(), any(), null) } coAnswers {
            operation = WebhookOperation(arg(3), arg(4), null, now)
        }
        val input = JsonObject().put("name", "bot")
        service.create(11, requester, input, "create-obsolete")
        for (current in listOf(original.copy(executionId = nextExecutionId), original.copy(deleted = true))) {
            coEvery { repository.findForUpdate(connection, 7) } returns current
            val failure = runCatching { service.create(11, requester, input, "create-obsolete") }.exceptionOrNull()
            assertEquals(409, (failure as? WebhookRequestException)?.status)
        }
        coVerify(exactly = 1) { repository.create(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `재발급 재시도도 현재 관리 권한을 잃었으면 저장 결과를 조회하지 않는다`() = runBlocking {
        coEvery { guard.requireSettingsManager(connection, 11, requester) } throws ResourceAccessDeniedException("권한이 없습니다.")
        val failure = runCatching { service.rotateExecutionId(11, 7, requester, "retry") }.exceptionOrNull()
        assertTrue(failure is ResourceAccessDeniedException)
        coVerify(exactly = 0) { repository.findOperation(any(), any(), any()) }
        coVerify(exactly = 0) { repository.rotateExecutionId(any(), any()) }
    }

    @Test
    fun `실행 ID 재발급은 삭제되지 않은 TEXT 채널의 웹훅에만 허용한다`() = runBlocking {
        coEvery { channels.findForUpdate(connection, 11) } returns ChannelLifecycleProjection(11, 2, false, now, false, "VOICE")
        val voiceFailure = runCatching { service.rotateExecutionId(11, 7, requester, "voice") }.exceptionOrNull()
        assertEquals(400, (voiceFailure as? WebhookRequestException)?.status)
        coEvery { channels.findForUpdate(connection, 11) } returns ChannelLifecycleProjection(11, 2, true, now, false, "TEXT")
        val deletedChannel = runCatching { service.rotateExecutionId(11, 7, requester, "deleted-channel") }.exceptionOrNull()
        assertEquals(404, (deletedChannel as? WebhookRequestException)?.status)
        coEvery { channels.findForUpdate(connection, 11) } returns ChannelLifecycleProjection(11, 2, false, now, false, "TEXT")
        coEvery { repository.findForUpdate(connection, 7) } returns original.copy(deleted = true)
        val deletedWebhook = runCatching { service.rotateExecutionId(11, 7, requester, "deleted-webhook") }.exceptionOrNull()
        assertEquals(404, (deletedWebhook as? WebhookRequestException)?.status)
        coVerify(exactly = 0) { repository.rotateExecutionId(any(), any()) }
    }

    @Test
    fun `transaction 대기 중 권한이 회수되면 저장된 secret도 재생하지 않는다`() = runBlocking {
        coEvery { guard.requireSettingsManager(connection, 11, requester) } throws ResourceAccessDeniedException("권한이 없습니다.")
        assertTrue(runCatching { service.changeMode(11, 7, requester, JsonObject().put("isSecure", true), "retry") }.exceptionOrNull() is ResourceAccessDeniedException)
        coVerify(exactly = 0) { repository.findOperation(any(), any(), any()) }
    }

    @Test
    fun `같은 key의 다른 요청은 409로 거부한다`() = runBlocking {
        coEvery { repository.findOperation(connection, 3, "reused") } returns WebhookOperation("different", JsonObject(), null, now)
        val failure = runCatching { service.changeMode(11, 7, requester, JsonObject().put("isSecure", true), "reused") }.exceptionOrNull() as WebhookRequestException
        assertEquals(409, failure.status)
        coVerify(exactly = 0) { repository.changeMode(any(), any(), any(), any(), any()) }
    }
}
