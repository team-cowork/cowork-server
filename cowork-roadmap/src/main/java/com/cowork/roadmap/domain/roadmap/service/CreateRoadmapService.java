package com.cowork.roadmap.domain.roadmap.service;

import com.cowork.roadmap.domain.roadmap.presentation.data.request.CreateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;

public interface CreateRoadmapService {

    RoadmapResDto execute(Long userId, String userRole, CreateRoadmapReqDto request);
}
