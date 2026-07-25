package com.cowork.roadmap.domain.roadmap.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.UpdateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.ModifyRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;

@Service
public class ModifyRoadmapServiceImpl implements ModifyRoadmapService {

    private final RoadmapRepository roadmapRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport lookupSupport;

    public ModifyRoadmapServiceImpl(RoadmapRepository roadmapRepository,
            RoadmapAccessGuard accessGuard,
            RoadmapLookupSupport lookupSupport) {
        this.roadmapRepository = roadmapRepository;
        this.accessGuard = accessGuard;
        this.lookupSupport = lookupSupport;
    }

    @Override
    @Transactional
    public Mono<RoadmapResDto> execute(Long userId, String userRole, Long roadmapId, UpdateRoadmapReqDto request) {
        return lookupSupport.findRoadmapOrThrow(roadmapId)
                .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole).then(Mono.defer(() -> {
                    Roadmap updated = roadmap.toBuilder()
                            .title(request.title() != null ? request.title() : roadmap.getTitle())
                            .description(
                                    request.description() != null ? request.description() : roadmap.getDescription())
                            .category(request.category() != null ? request.category() : roadmap.getCategory())
                            .build();
                    updated.copyAuditFrom(roadmap);
                    updated.setLastModifiedBy(userId);
                    return roadmapRepository.save(updated).map(RoadmapResDto::from);
                })));
    }
}
