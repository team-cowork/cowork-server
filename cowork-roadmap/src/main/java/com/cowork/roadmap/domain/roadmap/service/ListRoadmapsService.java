package com.cowork.roadmap.domain.roadmap.service;

import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;

import reactor.core.publisher.Flux;

public interface ListRoadmapsService {

    Flux<RoadmapResDto> execute(RoadmapScope scope, String category, Long teamId, Long projectId);
}
