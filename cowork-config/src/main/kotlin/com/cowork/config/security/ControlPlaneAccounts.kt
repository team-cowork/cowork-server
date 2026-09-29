package com.cowork.config.security

import org.springframework.core.env.Environment
import org.springframework.security.core.userdetails.User
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.json.JsonMapper

class ControlPlaneAccount(val username: String, val passwordHash: String, val application: String, val profile: String)

class ControlPlaneAccounts(environment: Environment) {
    val accounts: Map<String, ControlPlaneAccount>

    init {
        val profiles = environment.activeProfiles.filter { it == "local" || it == "prod" }
        require(profiles.size == 1) { "Select exactly one Config Server deployment profile" }
        if (profiles.single() == "prod") {
            require(environment.getProperty("server.ssl.enabled", Boolean::class.java, false)) {
                "Enable TLS for the production Config Server"
            }
        }
        val input = environment.getRequiredProperty("CONFIG_SERVER_ACCOUNTS_JSON")
        // Never propagate JSON parse exceptions: their messages can contain the credential document.
        val parsed = try {
            val root = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(input)
            require(root.isArray && root.size() in 1..100)
            (0 until root.size()).map { index ->
                val node = root.path(index)
                ControlPlaneAccount(
                    node.path("username").asText(),
                    node.path("passwordHash").asText(),
                    node.path("application").asText(),
                    node.path("profile").asText(),
                )
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid CONFIG_SERVER_ACCOUNTS_JSON")
        }
        require(
            parsed.all {
                USERNAME.matches(it.username) && HASH.matches(it.passwordHash) &&
                    it.application in APPLICATIONS && it.profile == profiles.single()
            },
        ) { "Invalid Config Server account, hash, application or profile" }
        require(parsed.map { it.username }.distinct().size == parsed.size) { "Duplicate Config Server username" }
        accounts = parsed.associateBy { it.username }
    }

    fun userDetailsService(): InMemoryUserDetailsManager = InMemoryUserDetailsManager(
        accounts.values.map { User.withUsername(it.username).password(it.passwordHash).roles("CLIENT").build() },
    )

    companion object {
        private val USERNAME = Regex("[a-z][a-z0-9-]{2,63}")

        // Spring Security PBKDF2 v5.8: 16-byte salt + 32-byte SHA-256 digest, hex encoded.
        private val HASH = Regex("[a-f0-9]{96}")
        private val APPLICATIONS = setOf(
            "cowork-gateway", "cowork-authorization", "cowork-user", "cowork-team", "cowork-channel",
            "cowork-project", "cowork-roadmap", "cowork-notification", "cowork-preference", "cowork-chat",
            "cowork-voice", "monitoring",
        )
    }
}
