package com.cowork.roadmap.domain.roadmap.service;

import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapTreeResDto;

public interface QueryRoadmapTreeService {

    RoadmapTreeResDto execute(Long userId, Long roadmapId);
}
