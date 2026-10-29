package com.cowork.gateway.webhook

import io.lettuce.core.RedisClient
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.output.IntegerListOutput
import io.lettuce.core.protocol.CommandArgs
import io.lettuce.core.protocol.ProtocolKeyword
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.nio.charset.StandardCharsets
import java.time.Duration

@Component
class DataGsmWebhookQueue(connectionFactory: LettuceConnectionFactory) {
    private val client = connectionFactory.requiredNativeClient as RedisClient

    // A dedicated connection keeps XADD and WAITAOF on the same Redis client connection.
    fun accept(body: ByteArray, signature: String): Mono<Boolean> = Mono.usingWhen(
        Mono.fromCallable { client.connect(StringCodec.UTF8) }.subscribeOn(Schedulers.boundedElastic()),
        { connection ->
            val commands = connection.reactive()
            // Keep the original signature. Authorization using a new DATAGSM_WEBHOOK_SECRET
            // treats signatures from the previous key as invalid and discards those queued entries.
            commands.xadd(
                STREAM_KEY,
                mapOf("body" to String(body, StandardCharsets.UTF_8), "signature" to signature),
            ).flatMap {
                val args = CommandArgs<String, String>(StringCodec.UTF8)
                    .add(1L).add(0L).add(2000L)
                commands.dispatch<Long>(WAIT_AOF, IntegerListOutput(StringCodec.UTF8), args)
                    .collectList()
                    .map { result -> result.size == 2 && result[0] >= 1L }
            }
        },
        { connection -> Mono.fromCompletionStage(connection.closeAsync()).then() },
    ).timeout(Duration.ofSeconds(5))

    companion object {
        const val STREAM_KEY = "cowork:authorization:datagsm:webhooks"
        private val WAIT_AOF = object : ProtocolKeyword {
            override fun getBytes(): ByteArray = "WAITAOF".toByteArray(StandardCharsets.US_ASCII)
        }
    }
}
