package com.cowork.channel.domain.channel.presentation.data.request

import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChannelNameValidationTest {

    private val validator = Validation.buildDefaultValidatorFactory().validator

    private fun createErrors(name: String) = validator.validate(
        CreateChannelRequest(teamId = 1L, name = name, type = "TEXT", viewType = "TEXT"),
    ).map { it.message }

    private fun updateErrors(name: String?) = validator.validate(UpdateChannelRequest(name = name)).map { it.message }

    @Test
    fun `비었거나 공백뿐인 이름을 거부한다`() {
        listOf("", "   ", "\t\n", "　", " ").forEach { name ->
            assertEquals(listOf("채널 이름은 공백일 수 없습니다."), createErrors(name))
            assertEquals(listOf("채널 이름은 공백일 수 없습니다."), updateErrors(name))
        }
    }

    @Test
    fun `100자를 넘는 이름을 거부한다`() {
        listOf("가".repeat(101), "😀".repeat(101)).forEach { name ->
            assertEquals(listOf("채널 이름은 100자를 초과할 수 없습니다."), createErrors(name))
            assertEquals(listOf("채널 이름은 100자를 초과할 수 없습니다."), updateErrors(name))
        }
    }

    @Test
    fun `유효한 이름을 허용한다`() {
        listOf("가", "가".repeat(100), "😀".repeat(100), "개발 팀", " 개발 팀 ").forEach { name ->
            assertEquals(emptyList<String>(), createErrors(name))
            assertEquals(emptyList<String>(), updateErrors(name))
        }
    }

    @Test
    fun `수정 요청의 null 이름을 허용한다`() {
        assertEquals(emptyList<String>(), updateErrors(null))
    }
}
