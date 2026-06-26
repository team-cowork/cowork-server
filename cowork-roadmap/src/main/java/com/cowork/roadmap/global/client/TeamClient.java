package com.cowork.roadmap.global.client;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

/**
 * cowork-team 호출 클라이언트. 커스텀(팀/프로젝트) 로드맵의 권한 검증에 사용한다. GET
 * /teams/{teamId}/members 응답에서 요청자의 팀 역할을 조회한다.
 */
@Component
public class TeamClient {

    private final WebClient teamWebClient;

    public TeamClient(WebClient teamWebClient) {
        this.teamWebClient = teamWebClient;
    }

    /**
     * 팀 멤버의 역할(OWNER/ADMIN/MEMBER)을 반환한다. 멤버가 아니면 empty.
     */
    public Mono<String> getMemberRole(Long teamId, Long userId) {
        return teamWebClient.get()
                .uri("/teams/{teamId}/members", teamId)
                .retrieve()
                .bodyToFlux(TeamMemberDto.class)
                .filter(member -> userId.equals(member.userId()))
                .next()
                .map(TeamMemberDto::role);
    }

    public record TeamMemberDto(Long userId, String role) {
    }
}
