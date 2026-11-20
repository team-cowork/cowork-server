package com.cowork.roadmap.domain.roadmap.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.service.QueryRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QueryRoadmapServiceImpl implements QueryRoadmapService {

    private final RoadmapAccessGuard roadmapAccessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;

    @Override
    @Transactional(readOnly = true)
    public RoadmapResDto execute(Long userId, Long roadmapId) {
        var roadmap = roadmapLookupSupport.findRoadmapOrThrow(roadmapId);
        roadmapAccessGuard.requireReadable(roadmap, userId);
        return RoadmapResDto.from(roadmap);
    }
}
