package com.cowork.global

import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.global.security.AuthUser
import com.cowork.global.security.JwtTokenProvider
import com.cowork.global.security.StompAuthChannelInterceptor
import com.cowork.user.domain.UserRole
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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
}
