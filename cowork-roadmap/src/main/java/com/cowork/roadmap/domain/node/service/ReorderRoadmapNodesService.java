package com.cowork.roadmap.domain.node.service;

import com.cowork.roadmap.domain.node.presentation.data.request.ReorderNodesReqDto;

import reactor.core.publisher.Mono;

public interface ReorderRoadmapNodesService {

    Mono<Void> execute(Long userId, String userRole, Long roadmapId, ReorderNodesReqDto request);
}
