package com.cowork.team.domain.team.service

import com.cowork.team.global.config.ObjectStorageProperties
import io.awspring.cloud.s3.S3Template
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.mockk
import org.springframework.http.HttpStatus
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import team.themoment.sdk.exception.ExpectedException

class S3ServiceTest :
    DescribeSpec({
        val publicBaseUrl = "https://cdn.example.com/team"
        lateinit var service: S3Service

        beforeEach {
            service = S3Service(
                mockk<S3Template>(relaxed = true),
                mockk<S3Presigner>(relaxed = true),
                mockk<S3Client>(relaxed = true),
                ObjectStorageProperties(
                    bucket = "team-icons",
                    publicBaseUrl = publicBaseUrl,
                ),
            )
        }

        describe("S3Service 클래스의") {
            describe("validateContentType 메서드는") {
                context("허용 목록에 없는 파일 형식이면") {
                    it("BAD_REQUEST로 거부한다") {
                        val error = shouldThrow<ExpectedException> {
                            service.validateContentType("image/svg+xml")
                        }

                        error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }
            }

            describe("buildObjectKey 메서드는") {
                context("허용된 이미지 형식이면") {
                    it("팀 아이콘 전용 경로와 올바른 확장자를 사용한다") {
                        service.buildObjectKey("image/jpeg") shouldStartWith "team-icons/"
                        service.buildObjectKey("image/png").substringAfterLast('.') shouldBe "png"
                        service.buildObjectKey("image/webp").substringAfterLast('.') shouldBe "webp"
                    }
                }
            }

            describe("validateIconUrl 메서드는") {
                context("팀이 관리하는 공개 URL이 아니면") {
                    it("BAD_REQUEST로 거부한다") {
                        val error = shouldThrow<ExpectedException> {
                            service.validateIconUrl("https://attacker.example/icon.png")
                        }

                        error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }
            }

            describe("confirmObject 메서드는") {
                context("object key가 팀 아이콘 경로 밖을 가리키면") {
                    it("스토리지 조회 전에 BAD_REQUEST로 거부한다") {
                        val error = shouldThrow<ExpectedException> {
                            service.confirmObject("chat-files/1/secret.png")
                        }

                        error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }
            }
        }
    })
