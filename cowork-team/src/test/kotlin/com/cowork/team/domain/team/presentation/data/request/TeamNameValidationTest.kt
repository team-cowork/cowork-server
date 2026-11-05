package com.cowork.team.domain.team.presentation.data.request

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import jakarta.validation.Validation

class TeamNameValidationTest :
    DescribeSpec({
        val validator = Validation.buildDefaultValidatorFactory().validator

        fun createErrors(name: String) = validator.validate(CreateTeamRequest(name, null, null)).map { it.message }

        fun updateErrors(name: String?) = validator.validate(UpdateTeamRequest(name, null, null)).map { it.message }

        describe("팀 이름 검증은") {
            mapOf(
                "빈 문자열" to "",
                "공백" to "   ",
                "공백 문자" to "\t\n",
                "전각 공백" to "　",
                "줄바꿈 없는 공백" to " ",
                "한글 초성 채움 문자" to "\u115F",
                "한글 중성 채움 문자" to "\u1160",
                "한글 채움 문자" to "\u3164",
                "반각 한글 채움 문자" to "\uFFA0",
                "폭 없는 공백" to "\u200B",
                "NEXT LINE 공백" to "\u0085",
                "카이티 형식 문자" to "\uD804\uDCBD\uD804\uDCCD",
                "이집트 상형문자 형식 제어" to "\uD80D\uDC30\uD80D\uDC3F",
                "속기 형식 제어" to "\uD82F\uDCA0\uD82F\uDCA3",
                "음악 형식 제어" to "\uD834\uDD73\uD834\uDD7A",
                "태그 문자" to "\uDB40\uDC01\uDB40\uDC20\uDB40\uDC7F",
                "BOM" to "\uFEFF",
                "점자 공백" to "\u2800",
                "공백과 보이지 않는 문자 혼합" to " \u200B\u3164\u2800\uFEFF ",
            ).forEach { (label, name) ->
                it("$label 이름을 거부한다") {
                    createErrors(name) shouldBe listOf("팀 이름은 공백일 수 없습니다.")
                    updateErrors(name) shouldBe listOf("팀 이름은 공백일 수 없습니다.")
                }
            }

            mapOf(
                "101자" to "가".repeat(101),
                "이모지 101자" to "😀".repeat(101),
                "앞뒤 공백을 제외한 101자" to " ${"가".repeat(101)} ",
            ).forEach { (label, name) ->
                it("$label 이름을 거부한다") {
                    createErrors(name) shouldBe listOf("팀 이름은 100자를 초과할 수 없습니다.")
                    updateErrors(name) shouldBe listOf("팀 이름은 100자를 초과할 수 없습니다.")
                }
            }

            mapOf(
                "1자" to "가",
                "100자" to "가".repeat(100),
                "이모지 100자" to "😀".repeat(100),
                "중간 공백" to "개발 팀",
                "앞뒤 공백" to " 개발 팀 ",
                "채움 문자와 일반 문자" to "\u3164팀",
                "형식 문자와 일반 문자" to "\u200B팀",
                "보충 평면 형식 문자와 일반 문자" to "\uDB40\uDC01팀\uD80D\uDC30",
                "보충 평면 형식 문자 다음 문자" to "\uD80D\uDC40",
                "ZWJ 이모지" to "👩‍💻",
                "앞뒤 공백을 제외한 100자" to " ${"가".repeat(100)} ",
                "앞뒤 공백을 제외한 이모지 100자" to " ${"😀".repeat(100)} ",
            ).forEach { (label, name) ->
                it("$label 이름을 허용한다") {
                    createErrors(name).shouldBeEmpty()
                    updateErrors(name).shouldBeEmpty()
                }
            }

            it("보이지 않는 문자가 길게 반복된 이름도 거부한다") {
                val expected = setOf("팀 이름은 공백일 수 없습니다.", "팀 이름은 100자를 초과할 수 없습니다.")
                listOf("\u200B".repeat(10000), "\uDB40\uDC01".repeat(10000)).forEach { name ->
                    createErrors(name).toSet() shouldBe expected
                    updateErrors(name).toSet() shouldBe expected
                }
            }

            it("앞뒤 공백을 제거하고 가운데 공백은 유지한다") {
                listOf(" 개발  팀 ", "\t개발  팀\n", "\u00A0\u3000개발  팀\u3000\u00A0").forEach { name ->
                    CreateTeamRequest(name, null, null).name shouldBe "개발  팀"
                    UpdateTeamRequest(name, null, null).name shouldBe "개발  팀"
                }
            }

            it("수정 요청의 null 이름을 허용한다") {
                UpdateTeamRequest(null, null, null).name shouldBe null
                updateErrors(null).shouldBeEmpty()
            }
        }
    })
