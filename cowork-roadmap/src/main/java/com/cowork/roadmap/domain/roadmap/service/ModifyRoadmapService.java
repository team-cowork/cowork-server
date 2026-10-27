package com.cowork.roadmap.domain.roadmap.service;

import com.cowork.roadmap.domain.roadmap.presentation.data.request.UpdateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;

public interface ModifyRoadmapService {

    RoadmapResDto execute(Long userId, String userRole, Long roadmapId, UpdateRoadmapReqDto request);
}
