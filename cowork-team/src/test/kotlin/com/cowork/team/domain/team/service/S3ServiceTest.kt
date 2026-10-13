package com.cowork.team.domain.team.service

import com.cowork.team.global.config.ObjectStorageProperties
import io.awspring.cloud.s3.S3Template
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import team.themoment.sdk.exception.ExpectedException
import java.util.function.Consumer

class S3ServiceTest :
    DescribeSpec({
        val publicBaseUrl = "https://cdn.example.com/team"
        lateinit var service: S3Service
        lateinit var client: S3Client

        beforeEach {
            client = mockk()
            service = S3Service(
                mockk<S3Template>(relaxed = true),
                mockk<S3Presigner>(relaxed = true),
                client,
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
                it("같은 공개 주소의 채팅 첨부나 프로필도 거부한다") {
                    listOf(
                        "chat-files/1/42/file.png",
                        "profiles/42/file.png",
                        "team-icons/../secret.png",
                    ).forEach { key ->
                        shouldThrow<ExpectedException> { service.validateIconUrl("$publicBaseUrl/$key") }
                            .statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }

                context("팀이 관리하는 공개 URL이 아니면") {
                    it("BAD_REQUEST로 거부한다") {
                        val error = shouldThrow<ExpectedException> {
                            service.validateIconUrl("https://attacker.example/icon.png")
                        }

                        error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }
            }

            describe("finalizeUpload 메서드는") {
                val key = "team-icons/12345678-1234-1234-1234-123456789012.png"
                val uploadKey = "team-icon-uploads/7/12345678-1234-1234-1234-123456789012.png"

                it("발급한 형식과 다른 임시 객체는 최종 경로에 공개하지 않는다") {
                    every { client.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns
                        HeadObjectResponse.builder().contentLength(100).contentType("image/jpeg").build()

                    shouldThrow<ExpectedException> { service.finalizeUpload(uploadKey, key, "image/png") }
                        .statusCode shouldBe HttpStatus.BAD_REQUEST
                    verify(exactly = 0) { client.copyObject(any<Consumer<CopyObjectRequest.Builder>>()) }
                }

                it("검증 후 변경된 임시 객체는 확정하지 않는다") {
                    every { client.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns
                        HeadObjectResponse.builder().contentLength(
                            100,
                        ).contentType("image/png").eTag("original").build()
                    every { client.copyObject(any<Consumer<CopyObjectRequest.Builder>>()) } answers {
                        val request = CopyObjectRequest.builder()
                        firstArg<Consumer<CopyObjectRequest.Builder>>().accept(request)
                        request.build().copySourceIfMatch() shouldBe "original"
                        throw S3Exception.builder().statusCode(412).build()
                    }

                    shouldThrow<ExpectedException> { service.finalizeUpload(uploadKey, key, "image/png") }
                        .statusCode shouldBe HttpStatus.CONFLICT
                }
            }

            describe("confirmObject 메서드는") {
                val key = "team-icons/12345678-1234-1234-1234-123456789012.png"

                it("크기 정보가 없으면 업로드 완료를 인정하지 않는다") {
                    every { client.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns
                        HeadObjectResponse.builder().contentType("image/png").build()
                    shouldThrow<ExpectedException> { service.confirmObject(key) }
                        .statusCode shouldBe HttpStatus.BAD_REQUEST
                }

                it("실제 크기가 한도를 초과하면 거부한다") {
                    every { client.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns
                        HeadObjectResponse.builder().contentLength(1048577).contentType("image/png").build()
                    shouldThrow<ExpectedException> { service.confirmObject(key) }
                        .statusCode shouldBe HttpStatus.CONTENT_TOO_LARGE
                }

                it("발급한 형식과 다른 객체는 완료를 인정하지 않는다") {
                    every { client.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns
                        HeadObjectResponse.builder().contentLength(100).contentType("image/jpeg").build()
                    shouldThrow<ExpectedException> { service.confirmObject(key, "image/png") }
                        .statusCode shouldBe HttpStatus.BAD_REQUEST
                }

                it("확인된 이미지의 URL만 반환한다") {
                    every { client.headObject(any<Consumer<HeadObjectRequest.Builder>>()) } returns
                        HeadObjectResponse.builder().contentLength(100).contentType("image/png").build()
                    service.confirmObject(key, "image/png") shouldBe "$publicBaseUrl/$key"
                }

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
