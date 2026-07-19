package com.cowork.roadmap.domain.roadmap.service;

import com.cowork.roadmap.domain.roadmap.presentation.data.request.CreateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;

import reactor.core.publisher.Mono;

public interface CreateRoadmapService {

    Mono<RoadmapResDto> execute(Long userId, String userRole, CreateRoadmapReqDto request);
}
