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

        describe("회의록 요약 제공자 선택") {
            val successBody = completion(mapper.writeValueAsString(expected))

            it("Groq 성공 시 한 번만 요청하고 구조화된 응답을 반환한다") {
                every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                    response(200, successBody)
                client.summarize("출시", "회의 내용") shouldBe expected
                requests.single().uri().host shouldBe "api.groq.com"
            }

            it("Groq의 429 이후 AI Hub로 한 번 전환하며 각 서비스의 키와 남은 시간을 사용한다") {
                every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returnsMany
                    listOf(response(429), response(200, successBody))
                client.summarize("출시", "회의 내용") shouldBe expected
                requests.map { it.uri().host } shouldBe listOf("api.groq.com", "jeb.https.gsmsv.site")
                requests.map { it.headers().firstValue("Authorization").get() } shouldBe
                    listOf("Bearer groq-key", "Bearer hub-key")
                (requests.last().timeout().get() <= requests.first().timeout().get()) shouldBe true
            }

            it("설정이 비어 있으면 각 서비스의 기본 모델로 같은 회의록을 보내고 AI Hub의 지원 필드만 사용한다") {
                val bodies = mutableListOf<Map<String, Any>>()
                val requestMapper = spyk(mapper)
                every { requestMapper.writeValueAsBytes(capture(bodies)) } answers { callOriginal() }
                client = MeetingNoteSummaryClient(http, requestMapper, "groq-key", "", "hub-key", "")
                every { http.send(any(), any<HttpResponse.BodyHandler<ByteArray>>()) } returnsMany
                    listOf(response(429), response(200, successBody))

                client.summarize("출시", "회의 내용")
                val (primary, fallback) = bodies
                primary["model"] shouldBe "openai/gpt-oss-120b"
                fallback["model"] shouldBe "flash-next"
                fallback["messages"] shouldBe primary["messages"]
                fallback.keys shouldBe setOf("model", "messages", "temperature", "max_tokens", "chat_template_kwargs")
            }

            it("두 제공자가 모두 호출을 제한하면 429를 반환한다") {
                every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returns response(429)
                shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode shouldBe
                    HttpStatus.TOO_MANY_REQUESTS
                requests.size shouldBe 2
            }

            it("대체 키가 없으면 Groq의 호출 제한을 그대로 반환한다") {
                client = MeetingNoteSummaryClient(http, mapper, "groq-key", "model", "", "model")
                every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returns response(429)
                shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode shouldBe
                    HttpStatus.TOO_MANY_REQUESTS
                requests.size shouldBe 1
            }

            for ((status, expectedStatus) in listOf(
                401 to HttpStatus.BAD_GATEWAY,
                503 to HttpStatus.SERVICE_UNAVAILABLE,
            )) {
                it("제공자의 $status 오류를 클라이언트 세션 만료로 처리하거나 다른 모델로 재시도하지 않는다") {
                    every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                        response(status)
                    shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode shouldBe expectedStatus
                    requests.size shouldBe 1
                }
            }

            it("응답 시간 초과는 504로 반환하고 재시도하지 않는다") {
                every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } throws
                    HttpTimeoutException("timeout")
                shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode shouldBe
                    HttpStatus.GATEWAY_TIMEOUT
                requests.size shouldBe 1
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
                it("불완전한 응답 $index 은 502로 반환한다") {
                    every { http.send(capture(requests), any<HttpResponse.BodyHandler<ByteArray>>()) } returns
                        response(200, malformed)
                    shouldThrow<ExpectedException> { client.summarize("", "회의") }.statusCode shouldBe
                        HttpStatus.BAD_GATEWAY
                    requests.size shouldBe 1
                }
            }
        }
    })
