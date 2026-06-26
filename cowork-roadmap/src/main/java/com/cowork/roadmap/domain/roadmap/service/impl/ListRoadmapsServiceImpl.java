package com.cowork.roadmap.domain.roadmap.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.ListRoadmapsService;

import reactor.core.publisher.Flux;

@Service
public class ListRoadmapsServiceImpl implements ListRoadmapsService {

    private final RoadmapRepository roadmapRepository;

    public ListRoadmapsServiceImpl(RoadmapRepository roadmapRepository) {
        this.roadmapRepository = roadmapRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<RoadmapResDto> execute(RoadmapScope scope, String category, Long teamId, Long projectId) {
        Flux<Roadmap> roadmaps;
        if (projectId != null) {
            roadmaps = roadmapRepository.findByOwnerProjectIdOrderByIdDesc(projectId);
        } else if (teamId != null) {
            roadmaps = roadmapRepository.findByOwnerTeamIdOrderByIdDesc(teamId);
        } else {
            RoadmapScope target = scope == null ? RoadmapScope.GLOBAL : scope;
            roadmaps = category == null
                    ? roadmapRepository.findByScopeOrderByIdDesc(target.name())
                    : roadmapRepository.findByScopeAndCategoryOrderByIdDesc(target.name(), category);
        }
        return roadmaps.map(RoadmapResDto::from);
    }
}
