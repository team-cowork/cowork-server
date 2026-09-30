package com.cowork.preference

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
        require(System.getenv("SPRING_PROFILES_ACTIVE") != "prod" || endpoint.scheme == "https") {
            "Use HTTPS for production Config/Eureka"
        }
        require(!username.isNullOrBlank() && !password.isNullOrBlank()) {
            "Provide CONFIG_CLIENT_USERNAME and CONFIG_CLIENT_PASSWORD"
        }
        return "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    }
}
