package com.cowork.project.domain.github.service

import com.cowork.project.global.client.PreferenceClient
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class GithubLabelPolicyReaderTest :
    DescribeSpec({

        lateinit var preferenceClient: PreferenceClient
        lateinit var reader: GithubLabelPolicyReader

        beforeEach {
            preferenceClient = mockk()
            reader = GithubLabelPolicyReader(preferenceClient)
        }

        describe("GithubLabelPolicyReader 클래스의") {
            describe("readAutoApply 메서드는") {
                context("설정값이 저장되어 있는 경우") {
                    it("저장된 값을 그대로 반환한다") {
                        every { preferenceClient.getGithubRepoSettings(5L) } returns mapOf("label_auto_apply" to false)

                        reader.readAutoApply(5L) shouldBe false
                    }
                }

                context("설정값이 아직 저장된 적 없는 경우") {
                    it("기본값(true)을 반환한다") {
                        every { preferenceClient.getGithubRepoSettings(5L) } returns emptyMap()

                        reader.readAutoApply(5L) shouldBe true
                    }
                }
            }
        }
    })
