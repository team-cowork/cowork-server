package com.cowork.roadmap.domain.node.service;

import reactor.core.publisher.Mono;

public interface DeleteRoadmapNodeService {

    Mono<Void> execute(Long userId, String userRole, Long nodeId);
}
