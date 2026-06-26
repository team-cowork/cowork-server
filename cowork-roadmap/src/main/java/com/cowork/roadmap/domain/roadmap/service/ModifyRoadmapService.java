package com.cowork.roadmap.domain.roadmap.service;

import com.cowork.roadmap.domain.roadmap.presentation.data.request.UpdateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;

import reactor.core.publisher.Mono;

public interface ModifyRoadmapService {

    Mono<RoadmapResDto> execute(Long userId, String userRole, Long roadmapId, UpdateRoadmapReqDto request);
}
