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
            ).forEach { (label, name) ->
                it("$label 이름을 거부한다") {
                    createErrors(name) shouldBe listOf("팀 이름은 공백일 수 없습니다.")
                    updateErrors(name) shouldBe listOf("팀 이름은 공백일 수 없습니다.")
                }
            }

            mapOf(
                "101자" to "가".repeat(101),
                "이모지 101자" to "😀".repeat(101),
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
            ).forEach { (label, name) ->
                it("$label 이름을 허용한다") {
                    createErrors(name).shouldBeEmpty()
                    updateErrors(name).shouldBeEmpty()
                }
            }

            it("수정 요청의 null 이름을 허용한다") {
                updateErrors(null).shouldBeEmpty()
            }
        }
    })
