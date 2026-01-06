package com.cowork.voice

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.global.error.BusinessException
import com.cowork.preference.service.PreferenceReader
import com.cowork.user.service.UserService
import com.cowork.voice.domain.VoiceParticipant
import com.cowork.voice.domain.VoiceSession
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class VoiceServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-01-10T03:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val sessionRepository = mockk<VoiceSessionRepository>()
    private val participantRepository = mockk<VoiceParticipantRepository>()
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val preferenceReader = mockk<PreferenceReader>()
    private val liveKitProperties = LiveKitProperties("ws://localhost:7880", "devkey", "test-livekit-secret-that-is-long-enough")
    private val service = VoiceService(
        sessionRepository,
        participantRepository,
        channelAccessChecker,
        preferenceReader,
        mockk<UserService>(relaxed = true),
        LiveKitTokenIssuer(liveKitProperties, clock),
        liveKitProperties,
        mockk<ApplicationEventPublisher>(relaxed = true),
        clock,
    )

    private val voiceChannel = Channel(
        teamId = 1,
        projectId = null,
        name = "voice",
        viewType = ChannelViewType.VOICE,
        description = null,
        isPrivate = false,
        position = 0,
        createdBy = 1,
        id = 3,
    )
    private val session = VoiceSession(channelId = 3, roomName = "channel-3-room", startedAt = LocalDateTime.now(clock), id = 100)

    @Test
    fun `정원이 가득 찬 음성 채널에는 참여할 수 없다`() {
        every { channelAccessChecker.requireReadable(3, 9) } returns voiceChannel
        every { sessionRepository.findFirstByChannelIdAndStatusOrderByIdDesc(3, VoiceSessionStatus.ACTIVE) } returns session
        every { participantRepository.findFirstBySessionIdAndUserIdAndLeftAtIsNull(100, 9) } returns null
        every { preferenceReader.findVoiceMaxParticipants(3) } returns 2
        every { participantRepository.countBySessionIdAndLeftAtIsNull(100) } returns 2

        val exception = assertThrows<BusinessException> { service.join(3, 9) }

        assertEquals(HttpStatus.CONFLICT, exception.status)
    }

    @Test
    fun `이미 참여 중이면 정원과 관계없이 토큰을 다시 발급한다`() {
        every { channelAccessChecker.requireReadable(3, 9) } returns voiceChannel
        every { sessionRepository.findFirstByChannelIdAndStatusOrderByIdDesc(3, VoiceSessionStatus.ACTIVE) } returns session
        every { participantRepository.findFirstBySessionIdAndUserIdAndLeftAtIsNull(100, 9) } returns
            VoiceParticipant(sessionId = 100, userId = 9, joinedAt = LocalDateTime.now(clock))

        val response = service.join(3, 9)

        assertEquals("channel-3-room", response.roomName)
        assertNotNull(response.token)
        verify(exactly = 0) { participantRepository.save(any()) }
    }

    @Test
    fun `마지막 참여자가 나가면 세션을 종료한다`() {
        val participant = VoiceParticipant(sessionId = 100, userId = 9, joinedAt = LocalDateTime.now(clock), id = 1)
        every { channelAccessChecker.findChannel(3) } returns voiceChannel
        every { sessionRepository.findFirstByChannelIdAndStatusOrderByIdDesc(3, VoiceSessionStatus.ACTIVE) } returns session
        every { participantRepository.findFirstBySessionIdAndUserIdAndLeftAtIsNull(100, 9) } returns participant
        every { participantRepository.findAllBySessionIdAndLeftAtIsNull(100) } returns listOf(participant)

        service.leave(3, 9)

        assertFalse(participant.isActive)
        assertEquals(VoiceSessionStatus.ENDED, session.status)
    }

    @Test
    fun `텍스트 채널에서는 음성에 참여할 수 없다`() {
        val textChannel = Channel(
            teamId = 1,
            projectId = null,
            name = "general",
            viewType = ChannelViewType.TEXT,
            description = null,
            isPrivate = false,
            position = 0,
            createdBy = 1,
            id = 4,
        )
        every { channelAccessChecker.requireReadable(4, 9) } returns textChannel

        assertThrows<BusinessException> { service.join(4, 9) }
    }
}
