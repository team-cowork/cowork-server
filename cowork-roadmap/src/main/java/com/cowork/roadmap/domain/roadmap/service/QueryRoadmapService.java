package com.cowork.roadmap.domain.roadmap.service;

import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;

public interface QueryRoadmapService {

    RoadmapResDto execute(Long userId, String userRole, Long roadmapId);
}
