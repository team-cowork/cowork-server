package com.cowork.roadmap.domain.roadmap.service;

import reactor.core.publisher.Mono;

public interface DeleteRoadmapService {

    Mono<Void> execute(Long userId, String userRole, Long roadmapId);
}
