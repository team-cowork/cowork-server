package com.cowork.channel.global.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.RequestPath
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.pattern.PathPatternParser
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 호스트 프록시가 요청을 버퍼링하지 않아 chunked 요청은 Gateway의 `RequestSize`를 거치지 않는다.
 * 회의록 생성·수정 요청은 본문을 [MAX_BODY_BYTES]까지만 읽고, 그보다 크면 Jackson에 넘기기 전에 413으로 거부한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class MeetingNoteBodySizeLimitFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val pattern =
            when (request.method) {
                HttpMethod.POST.name() -> CREATE_PATH
                HttpMethod.PATCH.name() -> UPDATE_PATH
                else -> return true
            }
        // Spring MVC처럼 디코딩하고 `;` 경로 파라미터를 뗀 경로로 비교해야 인코딩된 경로로 우회할 수 없다.
        val path = RequestPath.parse(request.requestURI, request.contextPath).pathWithinApplication()
        return !pattern.matches(path)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > MAX_BODY_BYTES) {
            rejectTooLarge(response)
            return
        }
        val body = readUpTo(request.inputStream, MAX_BODY_BYTES)
        if (body == null) {
            rejectTooLarge(response)
            return
        }
        filterChain.doFilter(CachedBodyRequest(request, body), response)
    }

    private fun rejectTooLarge(response: HttpServletResponse) {
        val status = HttpStatus.CONTENT_TOO_LARGE
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write("""{"status":"$status","code":${status.value()},"message":"$TOO_LARGE_MESSAGE"}""")
    }

    private class CachedBodyRequest(request: HttpServletRequest, private val body: ByteArray) :
        HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream = ByteArrayServletInputStream(body)

        override fun getReader(): BufferedReader {
            val charset = characterEncoding?.let(Charset::forName) ?: StandardCharsets.UTF_8
            return BufferedReader(InputStreamReader(inputStream, charset))
        }

        override fun getContentLength(): Int = body.size

        override fun getContentLengthLong(): Long = body.size.toLong()
    }

    private class ByteArrayServletInputStream(body: ByteArray) : ServletInputStream() {
        private val delegate = ByteArrayInputStream(body)

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun isFinished(): Boolean = delegate.available() == 0

        override fun isReady(): Boolean = true

        override fun setReadListener(readListener: ReadListener) = throw UnsupportedOperationException()
    }

    companion object {
        val MAX_BODY_BYTES: Long = DataSize.ofMegabytes(1).toBytes()

        private val CREATE_PATH = PathPatternParser.defaultInstance.parse("/channels/{channelId}/meeting-notes")
        private val UPDATE_PATH = PathPatternParser.defaultInstance.parse(
            "/channels/{channelId}/meeting-notes/{noteId}",
        )
        private const val TOO_LARGE_MESSAGE = "요청 본문이 허용 크기를 초과했습니다."

        /** 최대 [limit]+1바이트까지만 읽는다. [limit]를 넘으면 `null`을 돌려준다. */
        fun readUpTo(input: InputStream, limit: Long): ByteArray? {
            val bytes = input.readNBytes(Math.toIntExact(limit + 1))
            return if (bytes.size > limit) null else bytes
        }
    }
}
