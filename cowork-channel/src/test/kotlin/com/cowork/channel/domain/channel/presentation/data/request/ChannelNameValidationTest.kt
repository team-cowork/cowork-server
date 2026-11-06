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
    fun `비었거나 공백 또는 보이지 않는 문자뿐인 이름을 거부한다`() {
        listOf(
            "", "   ", "\t\n", "　", " ",
            "\u115F", "\u1160", "\u3164", "\uFFA0", "\u200B", "\uFEFF", "\u2800",
            " \u200B\u3164\u2800\uFEFF ",
            "\u0085",
            "\uD804\uDCBD\uD804\uDCCD",
            "\uD80D\uDC30\uD80D\uDC3F",
            "\uD82F\uDCA0\uD82F\uDCA3",
            "\uD834\uDD73\uD834\uDD7A",
            "\uDB40\uDC01\uDB40\uDC20\uDB40\uDC7F",
        ).forEach { name ->
            assertEquals(listOf("채널 이름은 공백일 수 없습니다."), createErrors(name))
            assertEquals(listOf("채널 이름은 공백일 수 없습니다."), updateErrors(name))
        }
    }

    @Test
    fun `100자를 넘는 이름을 거부한다`() {
        listOf("가".repeat(101), "😀".repeat(101), " ${"가".repeat(101)} ").forEach { name ->
            assertEquals(listOf("채널 이름은 100자를 초과할 수 없습니다."), createErrors(name))
            assertEquals(listOf("채널 이름은 100자를 초과할 수 없습니다."), updateErrors(name))
        }
    }

    @Test
    fun `유효한 이름을 허용한다`() {
        listOf(
            "가",
            "가".repeat(100),
            "😀".repeat(100),
            "개발 팀",
            " 개발 팀 ",
            "\u3164팀",
            "\u200B팀",
            "👩‍💻",
            "\uDB40\uDC01팀\uD80D\uDC30",
            "\uD80D\uDC40",
            " ${"가".repeat(100)} ",
            " ${"😀".repeat(100)} ",
        ).forEach { name ->
            assertEquals(emptyList<String>(), createErrors(name))
            assertEquals(emptyList<String>(), updateErrors(name))
        }
    }

    @Test
    fun `보이지 않는 문자가 길게 반복된 이름도 거부한다`() {
        val expected = setOf("채널 이름은 공백일 수 없습니다.", "채널 이름은 100자를 초과할 수 없습니다.")
        listOf("\u200B".repeat(10000), "\uDB40\uDC01".repeat(10000)).forEach { name ->
            assertEquals(expected, createErrors(name).toSet())
            assertEquals(expected, updateErrors(name).toSet())
        }
    }

    @Test
    fun `앞뒤 공백을 제거하고 가운데 공백은 유지한다`() {
        listOf(" 개발  팀 ", "\t개발  팀\n", "\u00A0\u3000개발  팀\u3000\u00A0").forEach { name ->
            val createRequest = CreateChannelRequest(teamId = 1L, name = name, type = "TEXT", viewType = "TEXT")
            assertEquals("개발  팀", createRequest.name)
            assertEquals("개발  팀", UpdateChannelRequest(name = name).name)
        }
    }

    @Test
    fun `수정 요청의 null 이름을 허용한다`() {
        assertEquals(null, UpdateChannelRequest(name = null).name)
        assertEquals(emptyList<String>(), updateErrors(null))
    }
}
