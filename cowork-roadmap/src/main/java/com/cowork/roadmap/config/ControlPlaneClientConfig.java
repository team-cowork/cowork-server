package com.cowork.roadmap.config;

import java.net.Inet4Address;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;

import org.springframework.cloud.netflix.eureka.http.EurekaClientHttpRequestFactorySupplier;
import org.springframework.cloud.netflix.eureka.http.RestClientDiscoveryClientOptionalArgs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ControlPlaneClientConfig {
    @Bean
    public RestClientDiscoveryClientOptionalArgs eurekaClientOptionalArgs(Environment environment) {
        String username = System.getenv("CONFIG_CLIENT_USERNAME");
        String password = System.getenv("CONFIG_CLIENT_PASSWORD");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Provide Config/Eureka bootstrap credentials");
        }
        URI endpoint = URI.create(environment.getRequiredProperty("eureka.client.service-url.defaultZone"));
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (endpoint.getUserInfo() != null || endpoint.getHost() == null
                || !("https".equals(endpoint.getScheme()) || ("http".equals(endpoint.getScheme())
                        && (!production || isPrivateIpv4(endpoint.getHost()))))) {
            throw new IllegalArgumentException(
                    "Use a Eureka URL without credentials and HTTPS or a private IPv4 HTTP URL in production");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        EurekaClientHttpRequestFactorySupplier factory = (sslContext, hostnameVerifier) -> {
            JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
            requests.setReadTimeout(Duration.ofSeconds(8));
            return requests;
        };
        return new RestClientDiscoveryClientOptionalArgs(factory,
                () -> RestClient.builder().requestInterceptor((request, body, execution) -> {
                    if (!endpoint.getScheme().equals(request.getURI().getScheme())
                            || !endpoint.getRawAuthority().equals(request.getURI().getRawAuthority())) {
                        throw new IllegalArgumentException("Reject a redirected Eureka endpoint");
                    }
                    request.getHeaders().setBasicAuth(username, password);
                    return execution.execute(request, body);
                }));
    }

    // ofLiteral never resolves DNS, and isSiteLocalAddress matches exactly the RFC1918 IPv4 ranges.
    private static boolean isPrivateIpv4(String host) {
        try {
            return Inet4Address.ofLiteral(host).isSiteLocalAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
