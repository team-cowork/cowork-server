package com.cowork.notification.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Component
class SseEmitterRegistry {

    private val log = LoggerFactory.getLogger(javaClass)
    private val emitters = ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>()

    fun subscribe(userId: Long): SseEmitter {
        val emitter = SseEmitter(TIMEOUT_MILLIS)
        val userEmitters = emitters.computeIfAbsent(userId) { CopyOnWriteArrayList() }
        userEmitters += emitter
        val remove = Runnable { remove(userId, emitter) }
        emitter.onCompletion(remove)
        emitter.onTimeout(remove)
        emitter.onError { remove.run() }
        send(userId, emitter, "connected", mapOf("userId" to userId))
        return emitter
    }

    fun send(userId: Long, eventName: String, data: Any): Boolean {
        val userEmitters = emitters[userId] ?: return false
        userEmitters.forEach { send(userId, it, eventName, data) }
        return userEmitters.isNotEmpty()
    }

    fun isConnected(userId: Long): Boolean = !emitters[userId].isNullOrEmpty()

    fun connectionCount(): Int = emitters.values.sumOf { it.size }

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MILLIS)
    fun heartbeat() {
        emitters.forEach { (userId, userEmitters) ->
            userEmitters.forEach { emitter ->
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"))
                } catch (e: IOException) {
                    remove(userId, emitter)
                }
            }
        }
    }

    private fun send(userId: Long, emitter: SseEmitter, eventName: String, data: Any) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data))
        } catch (e: IOException) {
            log.debug("Remove broken SSE emitter for user {}", userId)
            remove(userId, emitter)
        } catch (e: IllegalStateException) {
            remove(userId, emitter)
        }
    }

    private fun remove(userId: Long, emitter: SseEmitter) {
        emitters.computeIfPresent(userId) { _, list ->
            list.remove(emitter)
            list.ifEmpty { null }
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 30L * 60 * 1000
        private const val HEARTBEAT_INTERVAL_MILLIS = 30_000L
    }
}
