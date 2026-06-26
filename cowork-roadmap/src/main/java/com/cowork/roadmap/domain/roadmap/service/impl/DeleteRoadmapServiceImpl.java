package com.cowork.roadmap.domain.roadmap.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.DeleteRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;

@Service
public class DeleteRoadmapServiceImpl implements DeleteRoadmapService {

    private final RoadmapRepository roadmapRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport lookupSupport;

    public DeleteRoadmapServiceImpl(RoadmapRepository roadmapRepository,
            RoadmapAccessGuard accessGuard,
            RoadmapLookupSupport lookupSupport) {
        this.roadmapRepository = roadmapRepository;
        this.accessGuard = accessGuard;
        this.lookupSupport = lookupSupport;
    }

    @Override
    @Transactional
    public Mono<Void> execute(Long userId, String userRole, Long roadmapId) {
        return lookupSupport.findRoadmapOrThrow(roadmapId)
                .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                        .then(roadmapRepository.delete(roadmap)));
    }
}
