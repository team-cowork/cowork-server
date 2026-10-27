package com.cowork.roadmap.domain.roadmap.service;

import java.util.List;

import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;

public interface ListRoadmapsService {

    List<RoadmapResDto> execute(Long userId,
            String userRole,
            RoadmapScope scope,
            String category,
            Long teamId,
            Long projectId);
}
