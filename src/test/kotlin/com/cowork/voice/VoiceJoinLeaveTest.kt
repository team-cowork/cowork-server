package com.cowork.voice

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.preference.service.PreferenceReader
import com.cowork.user.dto.UserSummaryResponse
import com.cowork.user.service.UserService
import com.cowork.voice.domain.VoiceParticipant
import com.cowork.voice.domain.VoiceSessionStatus
import com.cowork.voice.livekit.LiveKitProperties
import com.cowork.voice.livekit.LiveKitTokenIssuer
import com.cowork.voice.repository.VoiceParticipantRepository
import com.cowork.voice.repository.VoiceSessionRepository
import com.cowork.voice.service.VoiceService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class VoiceJoinLeaveTest {

    private val sessionRepository = mockk<VoiceSessionRepository>()
    private val participantRepository = mockk<VoiceParticipantRepository>(relaxed = true)
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val preferenceReader = mockk<PreferenceReader>()
    private val userService = mockk<UserService>()
    private val tokenIssuer = mockk<LiveKitTokenIssuer>()
    private val service = VoiceService(
        sessionRepository, participantRepository, channelAccessChecker, preferenceReader, userService, tokenIssuer,
        LiveKitProperties(url = "wss://livekit", apiKey = "key", apiSecret = "secret-secret-secret-secret-secret"),
        mockk<ApplicationEventPublisher>(relaxed = true),
        Clock.fixed(Instant.parse("2026-01-05T11:00:00Z"), ZoneOffset.UTC),
    )

    private val channel = Channel(
        teamId = 1, projectId = null, name = "voice", viewType = ChannelViewType.VOICE, description = null,
        isPrivate = false, position = 0, createdBy = 1, id = 3,
    )

    @Test
    fun `진행 중인 세션이 없으면 새 세션을 만들어 참여한다`() {
        every { channelAccessChecker.requireReadable(3, 7) } returns channel
        every { sessionRepository.findFirstByChannelIdAndStatusOrderByIdDesc(3, VoiceSessionStatus.ACTIVE) } returns null
        every { sessionRepository.save(any()) } answers { firstArg() }
        every { participantRepository.findFirstBySessionIdAndUserIdAndLeftAtIsNull(any(), 7) } returns null
        every { participantRepository.countBySessionIdAndLeftAtIsNull(any()) } returns 0
        every { participantRepository.save(any<VoiceParticipant>()) } answers { firstArg() }
        every { preferenceReader.findVoiceMaxParticipants(3) } returns null
        every { userService.getSummaries(listOf(7)) } returns mapOf(7L to UserSummaryResponse(7, "홍길동", "길동", 2, 1, null, null))
        every { tokenIssuer.issue(any(), "7", "길동") } returns "token"

        val response = service.join(3, 7)

        assertTrue(response.roomName.startsWith("channel-3-"))
        assertEquals("wss://livekit", response.serverUrl)
        verify { tokenIssuer.issue(response.roomName, "7", "길동") }
    }
}
