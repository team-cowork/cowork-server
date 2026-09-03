package com.cowork.channel.global.config

import com.cowork.channel.global.consumer.Topics
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.PartitionInfo
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.kafka.support.SendResult
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

class ChannelRolePolicyCommandResultErrorHandlerTest {

    @Test
    fun `malformed result is published to DLT without retrying`() {
        val kafkaTemplate = mockKafkaTemplate()
        val errorHandler = ChannelRolePolicyCommandResultErrorHandler.create(kafkaTemplate)
        val consumer = mockk<Consumer<String, String>>()
        every {
            consumer.partitionsFor(Topics.CHANNEL_ROLE_POLICY_COMMAND_RESULT_DLT, any())
        } returns listOf(
            PartitionInfo(
                Topics.CHANNEL_ROLE_POLICY_COMMAND_RESULT_DLT,
                0,
                null,
                emptyArray(),
                emptyArray(),
            ),
        )
        val source = ConsumerRecord(
            Topics.CHANNEL_ROLE_POLICY_COMMAND_RESULT,
            0,
            1L,
            "operation-id",
            "malformed",
        )

        val recovered = errorHandler.handleOne(
            ListenerExecutionFailedException(
                "listener failed",
                "cowork-channel.channel-role-policy-command-result",
                IllegalArgumentException("malformed result"),
            ),
            source,
            consumer,
            mockk<MessageListenerContainer>(),
        )

        assertEquals(true, recovered)
        verify(exactly = 1) { kafkaTemplate.send(any<ProducerRecord<String, String>>()) }
    }

    @Test
    fun `result DLT recoverer preserves payload and adds no exception message or stack trace`() {
        val kafkaTemplate = mockKafkaTemplate()
        val published = slot<ProducerRecord<String, String>>()
        every { kafkaTemplate.send(capture(published)) } returns completedSend()
        val recoverer = ChannelRolePolicyCommandResultErrorHandler.deadLetterRecoverer(kafkaTemplate)
        val payload = "{\"operationId\":\"not-a-secret-copy\"}"
        val source = ConsumerRecord(
            Topics.CHANNEL_ROLE_POLICY_COMMAND_RESULT,
            2,
            41L,
            "operation-id",
            payload,
        )
        source.headers().add(
            KafkaHeaders.DLT_EXCEPTION_MESSAGE,
            "source-provided-sensitive-message".toByteArray(StandardCharsets.UTF_8),
        )
        source.headers().add(
            KafkaHeaders.DLT_EXCEPTION_STACKTRACE,
            "source-provided-sensitive-stacktrace".toByteArray(StandardCharsets.UTF_8),
        )

        recoverer.accept(source, IllegalArgumentException("must-not-be-added-to-headers"))

        verify(exactly = 1) { kafkaTemplate.send(any<ProducerRecord<String, String>>()) }
        assertEquals(Topics.CHANNEL_ROLE_POLICY_COMMAND_RESULT_DLT, published.captured.topic())
        assertEquals(2, published.captured.partition())
        assertEquals("operation-id", published.captured.key())
        assertEquals(payload, published.captured.value())
        assertArrayEquals(
            Topics.CHANNEL_ROLE_POLICY_COMMAND_RESULT.toByteArray(StandardCharsets.UTF_8),
            published.captured.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
        )
        assertNotNull(published.captured.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN))
        assertNull(published.captured.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE))
        assertNull(published.captured.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE))
    }

    @Test
    fun `result DLT recoverer rejects an unexpected source topic`() {
        val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
        every { kafkaTemplate.isTransactional } returns false
        val recoverer = ChannelRolePolicyCommandResultErrorHandler.deadLetterRecoverer(kafkaTemplate)
        val source = ConsumerRecord("unexpected.result", 0, 1L, "key", "value")

        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            recoverer.accept(source, IllegalStateException("failure"))
        }

        assertEquals(
            "channel role policy result DLT recoverer가 예상하지 않은 topic을 수신했습니다.",
            exception.message,
        )
        verify(exactly = 0) { kafkaTemplate.send(any<ProducerRecord<String, String>>()) }
    }

    private fun mockKafkaTemplate(): KafkaTemplate<String, String> {
        val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
        val producerFactory = mockk<ProducerFactory<String, String>>()
        every { kafkaTemplate.isTransactional } returns false
        every { kafkaTemplate.producerFactory } returns producerFactory
        every { producerFactory.configurationProperties } returns emptyMap()
        every { kafkaTemplate.send(any<ProducerRecord<String, String>>()) } returns completedSend()
        return kafkaTemplate
    }

    private fun completedSend(): CompletableFuture<SendResult<String, String>> =
        CompletableFuture.completedFuture(mockk<SendResult<String, String>>(relaxed = true))
}
