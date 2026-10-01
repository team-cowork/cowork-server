package com.cowork.gateway.response.body

import com.cowork.gateway.response.body.action.CollectAction
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

    private class BoundedResponseBodyCollector(private val maxBytes: Int) {
        private val buffers = mutableListOf<DataBuffer>()
        private var totalBytes = 0
        private var bypassing = false

        fun accept(dataBuffer: DataBuffer): CollectAction {
            if (bypassing) {
                return RelayCollectAction(dataBuffer)
            }

            if (dataBuffer.readableByteCount() == 0) {
                DataBufferUtils.release(dataBuffer)
                return HoldCollectAction
            }

            buffers += dataBuffer
            totalBytes = Math.addExact(totalBytes, dataBuffer.readableByteCount())
            if (totalBytes <= maxBytes) {
                return HoldCollectAction
            }

            bypassing = true
            val pendingBuffers = buffers.toList()
            buffers.clear()
            return EmitCollectAction(pendingBuffers, totalBytes)
        }

        fun complete(onEmpty: () -> Unit, onComplete: (ByteArray) -> DataBuffer): Flux<DataBuffer> {
            if (bypassing) {
                return Flux.empty()
            }

            val pendingBuffers = buffers.toList()
            buffers.clear()
            if (totalBytes == 0) {
                releaseAll(pendingBuffers)
                onEmpty()
                return Flux.empty()
            }

            return try {
                val bytes = ByteArray(totalBytes)
                var offset = 0
                pendingBuffers.forEach { dataBuffer ->
                    val readableBytes = dataBuffer.readableByteCount()
                    dataBuffer.read(bytes, offset, readableBytes)
                    offset += readableBytes
                }
                Flux.just(onComplete(bytes))
            } finally {
                releaseAll(pendingBuffers)
            }
        }

        fun releasePending() {
            releaseAll(buffers)
            buffers.clear()
        }

        private fun releaseAll(dataBuffers: Iterable<DataBuffer>) {
            dataBuffers.forEach(DataBufferUtils::release)
        }
    }
}
