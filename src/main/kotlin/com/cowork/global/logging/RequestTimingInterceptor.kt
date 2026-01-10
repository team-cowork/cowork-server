package com.cowork.global.logging

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 처리 시간이 기준을 넘은 요청을 경고 로그로 남긴다.
 */
@Component
class RequestTimingInterceptor(
    @Value("\${cowork.logging.slow-request-millis:1000}") private val slowRequestMillis: Long,
) : HandlerInterceptor {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        request.setAttribute(START_ATTRIBUTE, System.nanoTime())
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        val start = request.getAttribute(START_ATTRIBUTE) as? Long ?: return
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        if (isSlow(elapsedMillis)) {
            log.warn("Slow request {} {} -> {} ({} ms)", request.method, request.requestURI, response.status, elapsedMillis)
        }
    }

    fun isSlow(elapsedMillis: Long): Boolean = elapsedMillis >= slowRequestMillis

    companion object {
        private const val START_ATTRIBUTE = "cowork.requestStartNanos"
    }
}
