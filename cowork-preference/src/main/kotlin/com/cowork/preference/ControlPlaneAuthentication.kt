package com.cowork.preference

import java.net.Inet4Address
import java.net.URI
import java.util.Base64

object ControlPlaneAuthentication {
    private val username = System.getenv("CONFIG_CLIENT_USERNAME")
    private val password = System.getenv("CONFIG_CLIENT_PASSWORD")

    fun authorization(url: String): String {
        val endpoint = URI.create(url)
        require(endpoint.userInfo == null && endpoint.host != null && endpoint.scheme in setOf("http", "https")) {
            "Use a Config/Eureka HTTP(S) URL without credentials"
        }
        val production = System.getenv("SPRING_PROFILES_ACTIVE") == "prod"
        require(!production || endpoint.scheme == "https" || isPrivateIpv4(endpoint.host)) {
            "Use HTTPS or a private IPv4 HTTP URL for production Config/Eureka"
        }
        require(!username.isNullOrBlank() && !password.isNullOrBlank()) {
            "Provide CONFIG_CLIENT_USERNAME and CONFIG_CLIENT_PASSWORD"
        }
        return "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    }

    // ofLiteral never resolves DNS, and isSiteLocalAddress matches exactly the RFC1918 IPv4 ranges.
    private fun isPrivateIpv4(host: String) =
        runCatching { Inet4Address.ofLiteral(host).isSiteLocalAddress }.getOrDefault(false)
}
