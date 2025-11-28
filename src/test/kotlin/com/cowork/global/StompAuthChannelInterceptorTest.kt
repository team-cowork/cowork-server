package com.cowork.global

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.global.security.AuthUser
import com.cowork.global.security.JwtTokenProvider
import com.cowork.global.security.StompAuthChannelInterceptor
import com.cowork.user.domain.UserRole
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageDeliveryException
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

class StompAuthChannelInterceptorTest {

    private val jwtTokenProvider = mockk<JwtTokenProvider>()
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val interceptor = StompAuthChannelInterceptor(jwtTokenProvider, channelAccessChecker)
    private val messageChannel = mockk<MessageChannel>()
    private val user = AuthUser(1, UserRole.MEMBER)

    private fun message(command: StompCommand, configure: StompHeaderAccessor.() -> Unit = {}): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(command)
        accessor.configure()
        accessor.setLeaveMutable(true)
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
    }

    private fun subscribe(destination: String) = message(StompCommand.SUBSCRIBE) {
        this.destination = destination
        this.user = UsernamePasswordAuthenticationToken(this@StompAuthChannelInterceptorTest.user, null, emptyList())
    }

    @Test
    fun `연결 시 토큰이 유효하면 사용자를 세션에 저장한다`() {
        every { jwtTokenProvider.parseAccessToken("token") } returns user
        val message = message(StompCommand.CONNECT) { addNativeHeader("Authorization", "Bearer token") }

        interceptor.preSend(message, messageChannel)

        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)!!
        assertEquals(user, (accessor.user as UsernamePasswordAuthenticationToken).principal)
    }

    @Test
    fun `토큰 없이 연결하면 거부한다`() {
        assertThrows<MessageDeliveryException> { interceptor.preSend(message(StompCommand.CONNECT), messageChannel) }
    }

    @Test
    fun `읽을 수 없는 채널 토픽은 구독할 수 없다`() {
        val channel = Channel(
            teamId = 1, projectId = null, name = "기획", viewType = ChannelViewType.TEXT, description = null,
            isPrivate = true, position = 0, createdBy = 2, id = 10,
        )
        every { channelAccessChecker.findChannel(10) } returns channel
        every { channelAccessChecker.canRead(channel, 1) } returns false

        assertThrows<MessageDeliveryException> { interceptor.preSend(subscribe("/topic/channels/10"), messageChannel) }
    }

    @Test
    fun `사용자 상태 토픽과 개인 큐는 구독할 수 있다`() {
        assertDoesNotThrow { interceptor.preSend(subscribe("/topic/users/3/status"), messageChannel) }
        assertDoesNotThrow { interceptor.preSend(subscribe("/user/queue/errors"), messageChannel) }
    }

    @Test
    fun `정의하지 않은 경로는 구독할 수 없다`() {
        assertThrows<MessageDeliveryException> { interceptor.preSend(subscribe("/topic/admin"), messageChannel) }
    }
}
