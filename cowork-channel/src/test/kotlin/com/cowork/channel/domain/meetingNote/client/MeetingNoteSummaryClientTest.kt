package com.cowork.channel.domain.meetingNote.client

import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteSummaryResponse.ActionItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MeetingNoteSummaryClientTest :
    DescribeSpec({
        val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
        lateinit var http: HttpClient
        lateinit var client: MeetingNoteSummaryClient
        val requests = mutableListOf<HttpRequest>()
        val expected = MeetingNoteSummaryClient.Summary("출시를 승인했습니다.", listOf("금요일 출시"), listOf(ActionItem("공지", "")))

        fun completion(text: String, reason: String = "stop") = mapper.writeValueAsBytes(
            mapOf(
                "choices" to listOf(
                    mapOf(
                        "finish_reason" to reason,
                        "message" to mapOf("content" to text, "reasoning_content" to "비공개"),
                    ),
                ),
            ),
        )

        fun response(status: Int, body: ByteArray = byteArrayOf()): HttpResponse<ByteArray> = mockk {
            every { statusCode() } returns status
            every { body() } returns body
        }

        beforeEach {
            requests.clear()
            http = mockk()
            client = MeetingNoteSummaryClient(http, mapper, "groq-key", "groq-model", "hub-key", "hub-model")
        }

        describe("MeetingNoteSummaryClient 클래스의 summarize 메서드는") {
            val successBody = completion(mapper.writeValueAsString(expected))

            context("외부 응답을 기다리는 요청이 10건이면") {
                it("추가 요청을 즉시 거부하고 외부 호출이 실패한 뒤에는 다시 요약한다") {
                    val inFlight = CountDownLatch(10)
                    val finish = CountDownLatch(1)
                    every { http.send(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } answers {
                        inFlight.countDown()
                        check(finish.await(10, TimeUnit.SECONDS))
                        throw HttpTimeoutException("timeout")
                    }
                    Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                        val calls = List(10) {
                            executor.submit<HttpStatus> {
                                shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode
                            }
                        }
                        try {
                            inFlight.await(10, TimeUnit.SECONDS) shouldBe true
                            shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode shouldBe
                                HttpStatus.SERVICE_UNAVAILABLE
                        } finally {
                            finish.countDown()
                        }
                        calls.forEach { it.get(10, TimeUnit.SECONDS) shouldBe HttpStatus.GATEWAY_TIMEOUT }
                    }

                    every { http.send(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                        response(200, successBody)
                    client.summarize("", "회의") shouldBe expected
                }
            }

            context("Groq가 성공하면") {
                it("한 번만 요청하고 구조화된 응답을 반환한다") {
                    every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                        response(200, successBody)
                    client.summarize("출시", "회의 내용") shouldBe expected
                    requests.single().uri().host shouldBe "api.groq.com"
                }
            }

            val tokenLimit = """{"error":{"code":"rate_limit_exceeded"}}""".toByteArray()
            for ((status, body) in listOf(429 to byteArrayOf(), 413 to tokenLimit)) {
                context("Groq가 호출 제한을 ${status}로 반환하면") {
                    it("각 서비스의 키와 남은 시간으로 AI Hub에 한 번 요청한다") {
                        every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returnsMany
                            listOf(response(status, body), response(200, successBody))
                        client.summarize("출시", "회의 내용") shouldBe expected
                        requests.map { it.uri().host } shouldBe listOf("api.groq.com", "jeb.https.gsmsv.site")
                        requests.map { it.headers().firstValue("Authorization").get() } shouldBe
                            listOf("Bearer groq-key", "Bearer hub-key")
                        (requests.last().timeout().get() <= requests.first().timeout().get()) shouldBe true
                    }
                }
            }

            context("모델 설정이 비어 있으면") {
                it("각 서비스의 기본 모델에 같은 회의록과 지원 필드를 보낸다") {
                    val bodies = mutableListOf<Map<String, Any>>()
                    val requestMapper = spyk(mapper)
                    every { requestMapper.writeValueAsBytes(capture(bodies)) } answers { callOriginal() }
                    client = MeetingNoteSummaryClient(http, requestMapper, "groq-key", "", "hub-key", "")
                    every { http.send(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returnsMany
                        listOf(response(429), response(200, successBody))

                    client.summarize("출시", "회의 내용")
                    val (primary, fallback) = bodies
                    primary["model"] shouldBe "openai/gpt-oss-120b"
                    primary["reasoning_effort"] shouldBe "low"
                    primary["max_completion_tokens"] shouldBe 4096
                    fallback["model"] shouldBe "flash-next"
                    fallback["messages"] shouldBe primary["messages"]
                    fallback.keys shouldBe
                        setOf("model", "messages", "temperature", "max_tokens", "chat_template_kwargs")
                }
            }

            context("다른 Groq 모델을 설정하면") {
                it("GPT-OSS 전용 추론 설정을 보내지 않는다") {
                    val bodies = mutableListOf<Map<String, Any>>()
                    val requestMapper = spyk(mapper)
                    every { requestMapper.writeValueAsBytes(capture(bodies)) } answers { callOriginal() }
                    client = MeetingNoteSummaryClient(http, requestMapper, "groq-key", "custom-model", "", "")
                    every { http.send(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                        response(200, successBody)

                    client.summarize("", "회의")
                    val primary = bodies.single()
                    primary["model"] shouldBe "custom-model"
                    primary.containsKey("reasoning_effort") shouldBe false
                    primary["max_completion_tokens"] shouldBe 2048
                }
            }

            context("두 제공자가 모두 호출을 제한하면") {
                it("429를 반환한다") {
                    every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                        response(429)
                    shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode shouldBe
                        HttpStatus.TOO_MANY_REQUESTS
                    requests.size shouldBe 2
                }
            }

            for ((status, body) in listOf(429 to byteArrayOf(), 413 to tokenLimit)) {
                context("대체 키가 없고 Groq가 호출 제한을 ${status}로 반환하면") {
                    it("Groq의 호출 제한을 그대로 반환한다") {
                        client = MeetingNoteSummaryClient(http, mapper, "groq-key", "model", "", "model")
                        every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                            response(status, body)
                        shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode shouldBe
                            HttpStatus.TOO_MANY_REQUESTS
                        requests.size shouldBe 1
                    }
                }
            }

            context("Groq의 413 응답이 토큰 한도 초과를 나타내지 않으면") {
                for (body in listOf("""{"error":{"code":"request_too_large"}}""", """{"error":{}}""", "invalid", "")) {
                    it("대체 호출 없이 제공자 오류로 처리한다 (본문: '$body')") {
                        every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                            response(413, body.toByteArray())
                        shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode shouldBe
                            HttpStatus.BAD_GATEWAY
                        requests.size shouldBe 1
                    }
                }
            }

            for ((status, expectedStatus) in listOf(
                401 to HttpStatus.BAD_GATEWAY,
                503 to HttpStatus.SERVICE_UNAVAILABLE,
            )) {
                context("제공자가 $status 오류를 반환하면") {
                    it("다른 모델로 재시도하지 않고 $expectedStatus 오류를 반환한다") {
                        every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                            response(status)
                        shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode shouldBe expectedStatus
                        requests.size shouldBe 1
                    }
                }
            }

            context("응답 시간이 초과되면") {
                it("재시도 없이 504를 반환한다") {
                    every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } throws
                        HttpTimeoutException("timeout")
                    shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode shouldBe
                        HttpStatus.GATEWAY_TIMEOUT
                    requests.size shouldBe 1
                }
            }

            listOf(
                completion(mapper.writeValueAsString(expected), "length"),
                completion("{}"),
                completion("null"),
                completion("invalid"),
                completion("""{"summary":"회의","decisions":[],"actionItems":[null]}"""),
                completion("""{"summary":"","decisions":[],"actionItems":[]}"""),
                byteArrayOf(),
            ).forEachIndexed { index, malformed ->
                context("제공자의 응답이 불완전하면 (사례 $index)") {
                    it("재시도 없이 502를 반환한다") {
                        every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                            response(200, malformed)
                        shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode shouldBe
                            HttpStatus.BAD_GATEWAY
                        requests.size shouldBe 1
                    }
                }
            }
        }
    })
