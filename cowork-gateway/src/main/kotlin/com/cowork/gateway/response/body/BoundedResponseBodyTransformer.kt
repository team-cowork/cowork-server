package com.cowork.gateway.response.body

import com.cowork.gateway.response.body.action.EmitCollectAction
import com.cowork.gateway.response.body.action.HoldCollectAction
import com.cowork.gateway.response.body.action.RelayCollectAction
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean

internal class BoundedResponseBodyTransformer(private val maxBytes: Int) {

    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    fun transform(
        body: Publisher<out DataBuffer>,
        onThresholdExceeded: (Int) -> Unit = {},
        onEmpty: () -> Unit = {},
        onComplete: (ByteArray) -> DataBuffer,
    ): Flux<DataBuffer> {
        val collector = BoundedResponseBodyCollector(maxBytes)
        val thresholdRecorded = AtomicBoolean()

        return Flux.from(body)
            .concatMap({ dataBuffer ->
                when (val action = collector.accept(dataBuffer)) {
                    HoldCollectAction -> Mono.empty()

                    is EmitCollectAction -> {
                        if (thresholdRecorded.compareAndSet(false, true)) {
                            onThresholdExceeded(action.totalBytes)
                        }
                        Flux.fromIterable(action.buffers)
                    }

                    is RelayCollectAction -> Mono.just(action.dataBuffer)
                }
            }, 1)
            .concatWith(
                Flux.defer {
                    collector.complete(onEmpty, onComplete)
                },
            )
            .doOnDiscard(DataBuffer::class.java, DataBufferUtils::release)
            .doFinally {
                collector.releasePending()
            }
    }
}
