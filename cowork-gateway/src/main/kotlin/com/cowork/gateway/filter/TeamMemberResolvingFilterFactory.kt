package com.cowork.gateway.filter

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import java.time.Duration

@Component
class TeamMemberResolvingFilterFactory(
    @Qualifier("lbWebClientBuilder") webClientBuilder: WebClient.Builder,
) : AbstractGatewayFilterFactory<TeamMemberResolvingFilterFactory.Config>(Config::class.java) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val client: WebClient = webClientBuilder.build()

    override fun apply(config: Config): GatewayFilter = GatewayFilter { exchange, chain ->
        val queryParams = exchange.request.queryParams
        val teamId = queryParams.getFirst("teamId")

        // user_ids는 게이트웨이 내부 파라미터 — teamId 없이 클라이언트가 직접 지정하는 경우 제거
        if (teamId == null) {
            if (!queryParams.containsKey("user_ids")) return@GatewayFilter chain.filter(exchange)

            val stripped = LinkedMultiValueMap<String, String>()
            queryParams.forEach { key, values -> if (key != "user_ids") stripped[key] = values }
            val strippedUri = UriComponentsBuilder.fromUri(exchange.request.uri)
                .replaceQueryParams(stripped)
                .build()
                .toUri()
            return@GatewayFilter chain.filter(
                exchange.mutate().request(exchange.request.mutate().uri(strippedUri).build()).build()
            )
        }

        val userId = exchange.request.headers.getFirst("X-User-Id")

        client.get()
            .uri("lb://cowork-team/teams/{teamId}/members", teamId)
            .headers { headers -> userId?.let { headers.set("X-User-Id", it) } }
            .retrieve()
            .bodyToFlux(TeamMemberDto::class.java)
            .map { it.userId.toString() }
            .collectList()
            .timeout(TEAM_SERVICE_TIMEOUT)
            .retry(2)
            .flatMap { userIds ->
                val newParams = LinkedMultiValueMap<String, String>()
                queryParams.forEach { key, values ->
                    if (key != "teamId" && key != "user_ids") newParams[key] = values
                }
                newParams["user_ids"] = listOf(userIds.joinToString(","))

                val newUri = UriComponentsBuilder.fromUri(exchange.request.uri)
                    .replaceQueryParams(newParams)
                    .build()
                    .toUri()

                val mutated = exchange.request.mutate().uri(newUri).build()
                chain.filter(exchange.mutate().request(mutated).build())
            }
            .onErrorResume { ex ->
                log.error("팀 멤버 조회 실패 teamId={}", teamId, ex)
                val status = if (ex is WebClientResponseException) ex.statusCode else HttpStatus.SERVICE_UNAVAILABLE
                exchange.response.statusCode = status
                exchange.response.setComplete()
            }
    }

    private data class TeamMemberDto(val userId: Long)

    class Config

    companion object {
        private val TEAM_SERVICE_TIMEOUT = Duration.ofSeconds(3)
    }
}
