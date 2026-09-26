package com.cowork.gateway.response.body

import com.cowork.gateway.response.body.action.CollectAction
import com.cowork.gateway.response.body.action.EmitCollectAction
import com.cowork.gateway.response.body.action.HoldCollectAction
import com.cowork.gateway.response.body.action.RelayCollectAction
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import reactor.core.publisher.Flux

internal class BoundedResponseBodyCollector(private val maxBytes: Int) {
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
