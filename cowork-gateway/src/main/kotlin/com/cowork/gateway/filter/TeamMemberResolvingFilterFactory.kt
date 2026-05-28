package com.cowork.gateway.filter

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.UriComponentsBuilder

@Component
class TeamMemberResolvingFilterFactory(
    @Qualifier("lbWebClientBuilder") webClientBuilder: WebClient.Builder,
) : AbstractGatewayFilterFactory<TeamMemberResolvingFilterFactory.Config>(Config::class.java) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val client: WebClient = webClientBuilder.build()

    override fun apply(config: Config): GatewayFilter = GatewayFilter { exchange, chain ->
        val teamId = exchange.request.queryParams.getFirst("teamId")
            ?: return@GatewayFilter chain.filter(exchange)

        client.get()
            .uri("lb://cowork-team/teams/{teamId}/members", teamId)
            .retrieve()
            .bodyToFlux(TeamMemberDto::class.java)
            .map { it.userId.toString() }
            .collectList()
            .flatMap { userIds ->
                val newParams = LinkedMultiValueMap<String, String>()
                exchange.request.queryParams.forEach { key, values ->
                    if (key != "teamId") newParams[key] = values
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
                exchange.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
                exchange.response.setComplete()
            }
    }

    private data class TeamMemberDto(val userId: Long)

    class Config
}
