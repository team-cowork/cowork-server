package com.cowork.voice.service

import com.cowork.voice.repository.VoiceParticipantRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 현재 음성 채널에 접속한 참여자 수를 게이지로 노출한다.
 */
@Component
class VoiceMetrics(
    voiceParticipantRepository: VoiceParticipantRepository,
    meterRegistry: MeterRegistry,
) {
    init {
        Gauge.builder("cowork.voice.participants") { voiceParticipantRepository.countByLeftAtIsNull().toDouble() }
            .description("현재 음성 채널 참여자 수")
            .register(meterRegistry)
    }
}
