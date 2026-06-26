package com.cowork.roadmap.domain.roadmap.service;

import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapTreeResDto;

import reactor.core.publisher.Mono;

public interface QueryRoadmapTreeService {

    Mono<RoadmapTreeResDto> execute(Long userId, String userRole, Long roadmapId);
}
