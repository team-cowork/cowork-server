package com.cowork.voice.service

import com.cowork.voice.domain.VoiceSessionStatus
import com.cowork.voice.repository.VoiceParticipantRepository
import com.cowork.voice.repository.VoiceSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * LiveKit 웹훅을 놓쳐 참여자가 없는데도 진행 중으로 남은 음성 세션을 끝낸다.
 */
@Component
class VoiceSessionCleanupScheduler(
    private val voiceSessionRepository: VoiceSessionRepository,
    private val voiceParticipantRepository: VoiceParticipantRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    @Transactional
    fun endEmptySessions() {
        val now = LocalDateTime.now(clock)
        val ended = voiceSessionRepository
            .findAllByStatusAndStartedAtBefore(VoiceSessionStatus.ACTIVE, now.minusMinutes(GRACE_MINUTES))
            .filter { voiceParticipantRepository.countBySessionIdAndLeftAtIsNull(it.id) == 0L }
        ended.forEach { it.end(now) }
        if (ended.isNotEmpty()) {
            log.info("End {} empty voice sessions", ended.size)
        }
    }

    companion object {
        private const val GRACE_MINUTES = 10L
    }
}
