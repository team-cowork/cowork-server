package com.cowork.gateway.filter

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
        val collector = Collector(maxBytes)
        val thresholdRecorded = AtomicBoolean()

        return Flux.from(body)
            .concatMap({ dataBuffer ->
                when (val action = collector.accept(dataBuffer)) {
                    CollectAction.Hold -> Mono.empty()
                    is CollectAction.Emit -> {
                        if (thresholdRecorded.compareAndSet(false, true)) {
                            onThresholdExceeded(action.totalBytes)
                        }
                        Flux.fromIterable(action.buffers)
                    }
                    is CollectAction.Relay -> Mono.just(action.dataBuffer)
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

    private sealed interface CollectAction {
        data object Hold : CollectAction

        data class Emit(val buffers: List<DataBuffer>, val totalBytes: Int) : CollectAction

        data class Relay(val dataBuffer: DataBuffer) : CollectAction
    }

    private class Collector(private val maxBytes: Int) {
        private val buffers = mutableListOf<DataBuffer>()
        private var totalBytes = 0
        private var bypassing = false

        fun accept(dataBuffer: DataBuffer): CollectAction {
            if (bypassing) {
                return CollectAction.Relay(dataBuffer)
            }

            if (dataBuffer.readableByteCount() == 0) {
                DataBufferUtils.release(dataBuffer)
                return CollectAction.Hold
            }

            buffers += dataBuffer
            totalBytes = Math.addExact(totalBytes, dataBuffer.readableByteCount())
            if (totalBytes <= maxBytes) {
                return CollectAction.Hold
            }

            bypassing = true
            val pendingBuffers = buffers.toList()
            buffers.clear()
            return CollectAction.Emit(pendingBuffers, totalBytes)
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
