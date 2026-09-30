package com.cowork.config.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/** Validate the body as well as the URL: Eureka takes the registered identity from the JSON. */
class EurekaRegistrationGuard(
    private val accounts: ControlPlaneAccounts,
    private val policy: ControlPlaneAccessPolicy,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val account = accounts.accounts[SecurityContextHolder.getContext().authentication?.name]
        val path = request.servletPath + (request.pathInfo ?: "")
        if (request.method != "POST" || !path.startsWith("/eureka/apps/")) {
            if (account != null && !path.startsWith("/eureka/") &&
                !path.startsWith("/actuator/")
            ) {
                log.info(
                    "Read configuration: client={} application={} profile={}",
                    account.username,
                    account.application,
                    account.profile,
                )
            }
            chain.doFilter(request, response)
            return
        }
        val body = request.inputStream.readNBytes(MAX_BODY_SIZE + 1)
        val allowed = account != null && body.size <= MAX_BODY_SIZE &&
            request.contentType?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) == true &&
            runCatching {
                val instance = mapper.readTree(body).path("instance")
                policy.allowsRegistration(
                    account.application,
                    instance.path("app").asText(),
                    instance.path("vipAddress").asText(),
                    instance.path("secureVipAddress").asText(),
                )
            }.getOrDefault(false)
        if (!allowed) {
            log.warn("Reject Eureka registration identity: remote={}", request.remoteAddr)
            response.status = 403
            return
        }
        chain.doFilter(ReplayableRequest(request, body), response)
    }

    private class ReplayableRequest(request: HttpServletRequest, private val body: ByteArray) :
        HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val input = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = input.read()
                override fun isFinished(): Boolean = input.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener) =
                    error("Asynchronous body reads are not supported")
            }
        }

        override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
    }

    companion object {
        private const val MAX_BODY_SIZE = 65536
    }
}
