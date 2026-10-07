package com.cowork.channel.config

import org.springframework.cloud.netflix.eureka.http.EurekaClientHttpRequestFactorySupplier
import org.springframework.cloud.netflix.eureka.http.RestClientDiscoveryClientOptionalArgs
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.Inet4Address
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

@Configuration
class ControlPlaneClientConfig {
    @Bean
    fun eurekaClientOptionalArgs(environment: Environment): RestClientDiscoveryClientOptionalArgs {
        val username = System.getenv("CONFIG_CLIENT_USERNAME")
        val password = System.getenv("CONFIG_CLIENT_PASSWORD")
        require(!username.isNullOrBlank() && !password.isNullOrBlank()) {
            "Provide Config/Eureka bootstrap credentials"
        }
        val endpoint = URI.create(environment.getRequiredProperty("eureka.client.service-url.defaultZone"))
        require(endpoint.userInfo == null && endpoint.host != null) { "Use a Eureka URL without credentials" }
        val privateHttp = "prod" !in environment.activeProfiles || isPrivateIpv4(endpoint.host)
        require(endpoint.scheme == "https" || (endpoint.scheme == "http" && privateHttp)) {
            "Use HTTPS or a private IPv4 HTTP URL for production Eureka"
        }
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val factory = EurekaClientHttpRequestFactorySupplier { _, _ ->
            JdkClientHttpRequestFactory(client).apply { setReadTimeout(Duration.ofSeconds(8)) }
        }
        // Scope the Authorization header to Eureka, including retry and registry requests.
        return RestClientDiscoveryClientOptionalArgs(factory) {
            RestClient.builder().requestInterceptor { request, body, execution ->
                require(request.uri.scheme == endpoint.scheme && request.uri.rawAuthority == endpoint.rawAuthority) {
                    "Reject a redirected Eureka endpoint"
                }
                request.headers.setBasicAuth(username, password)
                execution.execute(request, body)
            }
        }
    }
}

// ofLiteral never resolves DNS, and isSiteLocalAddress matches exactly the RFC1918 IPv4 ranges.
private fun isPrivateIpv4(host: String) =
    runCatching { Inet4Address.ofLiteral(host).isSiteLocalAddress }.getOrDefault(false)
