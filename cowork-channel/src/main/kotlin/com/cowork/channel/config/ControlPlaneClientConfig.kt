package com.cowork.channel.config

import org.springframework.cloud.netflix.eureka.http.EurekaClientHttpRequestFactorySupplier
import org.springframework.cloud.netflix.eureka.http.RestClientDiscoveryClientOptionalArgs
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
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
        require(endpoint.scheme == "https" || ("prod" !in environment.activeProfiles && endpoint.scheme == "http")) {
            "Use HTTPS for production Eureka"
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
