package com.cowork.channel.global.filter

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.ByteArrayInputStream

class MeetingNoteBodySizeLimitFilterTest :
    DescribeSpec({
        val limit = MeetingNoteBodySizeLimitFilter.MAX_BODY_BYTES
        val filter = MeetingNoteBodySizeLimitFilter()

        // chunked 요청처럼 Content-Length가 없는 요청을 흉내 낸다.
        class ChunkedRequest(method: String, uri: String) : MockHttpServletRequest(method, uri) {
            override fun getContentLength(): Int = -1

            override fun getContentLengthLong(): Long = -1
        }

        fun run(request: MockHttpServletRequest): Pair<MockHttpServletResponse, MockFilterChain> {
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(request, response, chain)
            return response to chain
        }

        describe("readUpTo") {
            it("상한 이하이면 본문 전체를 돌려준다") {
                val body = ByteArray(limit.toInt())
                MeetingNoteBodySizeLimitFilter.readUpTo(ByteArrayInputStream(body), limit)?.size shouldBe body.size
            }

            it("상한을 넘으면 null을 돌려준다") {
                val body = ByteArray(limit.toInt() + 1)
                MeetingNoteBodySizeLimitFilter.readUpTo(ByteArrayInputStream(body), limit).shouldBeNull()
            }
        }

        describe("회의록 생성·수정·요약 요청") {
            listOf(
                "POST" to "/channels/1/meeting-notes",
                "POST" to "/channels/1/meeting-notes/summary",
                "PATCH" to "/channels/1/meeting-notes/2",
                "POST" to "/channels/1/meeting%2Dnotes",
                "POST" to "/channels/1/meeting-notes;x=y",
                "PATCH" to "/channels/1/%6Deeting-notes/2",
            ).forEach { (method, uri) ->
                it("$method $uri: Content-Length가 상한을 넘으면 413으로 거부한다") {
                    val request = MockHttpServletRequest(method, uri).apply { setContent(ByteArray(limit.toInt() + 1)) }
                    val (response, chain) = run(request)
                    response.status shouldBe 413
                    chain.request.shouldBeNull()
                }

                it("$method $uri: Content-Length 없는 본문이 상한을 넘으면 413으로 거부한다") {
                    val request = ChunkedRequest(method, uri).apply { setContent(ByteArray(limit.toInt() + 1)) }
                    val (response, chain) = run(request)
                    response.status shouldBe 413
                    chain.request.shouldBeNull()
                }

                it("$method $uri: 상한 이하의 본문은 그대로 다음 필터로 넘긴다") {
                    val body = """{"title":"회의"}""".toByteArray()
                    val request = ChunkedRequest(method, uri).apply { setContent(body) }
                    val (response, chain) = run(request)
                    response.status shouldBe 200
                    chain.request.shouldNotBeNull().inputStream.readAllBytes() shouldBe body
                }
            }
        }

        describe("다른 요청") {
            listOf(
                "GET" to "/channels/1/meeting-notes",
                "POST" to "/channels/1/meeting-notes/2/attachments",
                "PATCH" to "/channels/1/threads/2",
            ).forEach { (method, uri) ->
                it("$method $uri 는 제한하지 않는다") {
                    val request = MockHttpServletRequest(method, uri).apply { setContent(ByteArray(limit.toInt() + 1)) }
                    val (response, chain) = run(request)
                    response.status shouldBe 200
                    chain.request shouldBe request
                }
            }
        }
    })
