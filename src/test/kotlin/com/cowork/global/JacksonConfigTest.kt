package com.cowork.global

import com.cowork.channel.dto.AddChannelMemberRequest
import com.cowork.channel.dto.UpdateWebhookRequest
import com.cowork.global.config.JacksonConfig
import com.cowork.team.dto.AddTeamMemberRequest
import com.fasterxml.jackson.databind.JsonMappingException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

class JacksonConfigTest {

    private val objectMapper = Jackson2ObjectMapperBuilder.json()
        .also { JacksonConfig().strictPrimitiveCustomizer().customize(it) }
        .build<com.fasterxml.jackson.databind.ObjectMapper>()

    @Test
    fun `ID를 빠뜨린 요청은 역직렬화하지 않는다`() {
        assertThrows<JsonMappingException> { objectMapper.readValue("{}", AddTeamMemberRequest::class.java) }
    }

    @Test
    fun `ID에 null을 보낸 요청은 역직렬화하지 않는다`() {
        assertThrows<JsonMappingException> { objectMapper.readValue("""{"userId":null}""", AddChannelMemberRequest::class.java) }
    }

    @Test
    fun `기본값이 있는 값은 빠뜨려도 기본값을 쓴다`() {
        assertEquals(false, objectMapper.readValue("{}", UpdateWebhookRequest::class.java).rotateToken)
    }

    @Test
    fun `정상 요청은 그대로 읽는다`() {
        assertEquals(7, objectMapper.readValue("""{"userId":7}""", AddTeamMemberRequest::class.java).userId)
    }
}
