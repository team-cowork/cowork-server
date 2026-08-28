package com.cowork.preference.messaging

import com.cowork.preference.repository.PreferenceOutboxDispatchResult
import com.cowork.preference.repository.PreferenceOutboxRepository
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class PreferenceOutboxDispatcher(
    private val vertx: Vertx,
    private val repository: PreferenceOutboxRepository,
    private val producer: PreferenceProducer,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(PreferenceOutboxDispatcher::class.java)
    private val dispatchInProgress = AtomicBoolean(false)
    private val cleanupInProgress = AtomicBoolean(false)
    private var dispatchTimerId: Long? = null
    private var cleanupTimerId: Long? = null
    private var closed = false

    fun start() {
        triggerDispatch()
        triggerCleanup()
        dispatchTimerId = vertx.setPeriodic(DISPATCH_INTERVAL_MS) { triggerDispatch() }
        cleanupTimerId = vertx.setPeriodic(CLEANUP_INTERVAL_MS) { triggerCleanup() }
    }

    fun close() {
        closed = true
        dispatchTimerId?.let(vertx::cancelTimer)
        cleanupTimerId?.let(vertx::cancelTimer)
    }

    private fun triggerDispatch() {
        if (closed || !dispatchInProgress.compareAndSet(false, true)) return
        scope.launch(vertx.dispatcher()) {
            try {
                drainAvailableRecords()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Preference outbox dispatch failed; oldest record will be retried", error)
            } finally {
                dispatchInProgress.set(false)
            }
        }
    }

    private suspend fun drainAvailableRecords() {
        repeat(MAX_RECORDS_PER_DRAIN) {
            if (closed) return
            when (repository.dispatchNextIfLeader(producer::publishOutboxEvent)) {
                PreferenceOutboxDispatchResult.PUBLISHED -> Unit
                PreferenceOutboxDispatchResult.EMPTY,
                PreferenceOutboxDispatchResult.BUSY,
                -> return
            }
        }
    }

    private fun triggerCleanup() {
        if (closed || !cleanupInProgress.compareAndSet(false, true)) return
        scope.launch(vertx.dispatcher()) {
            try {
                val deleted = repository.deletePublishedBefore(Instant.now().minus(PUBLISHED_RETENTION))
                if (deleted > 0) log.info("Deleted {} published preference outbox records", deleted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("Preference outbox cleanup failed; next scheduled run will retry", error)
            } finally {
                cleanupInProgress.set(false)
            }
        }
    }

    private companion object {
        const val DISPATCH_INTERVAL_MS = 250L
        const val CLEANUP_INTERVAL_MS = 3_600_000L
        const val MAX_RECORDS_PER_DRAIN = 500
        val PUBLISHED_RETENTION: Duration = Duration.ofDays(7)
    }
}
