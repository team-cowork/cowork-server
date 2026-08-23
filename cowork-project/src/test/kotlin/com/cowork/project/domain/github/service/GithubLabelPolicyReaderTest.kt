package com.cowork.project.domain.github.service

import com.cowork.project.global.client.PreferenceClient
import feign.FeignException
import feign.Request
import feign.RequestTemplate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import team.themoment.sdk.exception.ExpectedException

class GithubLabelPolicyReaderTest :
    DescribeSpec({

        lateinit var preferenceClient: PreferenceClient
        lateinit var reader: GithubLabelPolicyReader

        beforeEach {
            preferenceClient = mockk()
            reader = GithubLabelPolicyReader(preferenceClient)
        }

        fun feignException() = FeignException.NotFound(
            "not found",
            Request.create(Request.HttpMethod.GET, "/preferences/github-repo/5", emptyMap(), null, RequestTemplate()),
            null,
            emptyMap(),
        )

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

                context("cowork-preference 호출이 FeignException으로 실패하는 경우") {
                    it("BAD_GATEWAY ExpectedException으로 변환한다") {
                        every { preferenceClient.getGithubRepoSettings(5L) } throws feignException()

                        shouldThrow<ExpectedException> { reader.readAutoApply(5L) }
                    }
                }

                context("cowork-preference 호출이 로드밸런서 오류(FeignException 아님)로 실패하는 경우") {
                    it("BAD_GATEWAY ExpectedException으로 변환한다") {
                        every { preferenceClient.getGithubRepoSettings(5L) } throws
                            IllegalStateException("No instances available for cowork-preference")

                        shouldThrow<ExpectedException> { reader.readAutoApply(5L) }
                    }
                }
            }

            describe("writeAutoApply 메서드는") {
                context("응답에 저장된 값이 정상적으로 담겨오는 경우") {
                    it("저장된 값을 반환한다") {
                        every {
                            preferenceClient.updateGithubRepoSettings(5L, mapOf("label_auto_apply" to false))
                        } returns mapOf("label_auto_apply" to false)

                        reader.writeAutoApply(5L, false) shouldBe false
                    }
                }

                context("응답에 저장된 값이 담겨오지 않는 경우") {
                    it("저장 실패로 간주해 BAD_GATEWAY ExpectedException을 던진다") {
                        every {
                            preferenceClient.updateGithubRepoSettings(5L, mapOf("label_auto_apply" to false))
                        } returns emptyMap()

                        shouldThrow<ExpectedException> { reader.writeAutoApply(5L, false) }
                    }
                }
            }
        }
    })
