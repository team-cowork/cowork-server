package com.cowork.roadmap.domain.roadmap.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.DeleteRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DeleteRoadmapServiceImpl implements DeleteRoadmapService {

    private final RoadmapRepository roadmapRepository;
    private final RoadmapAccessGuard roadmapAccessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;

    @Override
    @Transactional
    public void execute(Long userId, Long roadmapId) {
        var roadmap = roadmapLookupSupport.findRoadmapForUpdateOrThrow(roadmapId);
        roadmapAccessGuard.requireMutable(roadmap, userId);
        roadmapRepository.delete(roadmap);
    }
}
