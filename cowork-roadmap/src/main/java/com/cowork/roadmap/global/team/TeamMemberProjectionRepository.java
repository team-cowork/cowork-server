package com.cowork.roadmap.global.team;

import reactor.core.publisher.Mono;

public interface TeamMemberProjectionRepository {

    Mono<String> findActiveRole(Long teamId, Long userId);

    Mono<Void> upsert(TeamMemberEvent event);

    Mono<Void> deactivate(TeamMemberEvent event);
}
