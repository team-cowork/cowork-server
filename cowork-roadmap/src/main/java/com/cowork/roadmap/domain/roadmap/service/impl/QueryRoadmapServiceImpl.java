package com.cowork.roadmap.domain.roadmap.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.service.QueryRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;

@Service
public class QueryRoadmapServiceImpl implements QueryRoadmapService {

    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport lookupSupport;

    public QueryRoadmapServiceImpl(RoadmapAccessGuard accessGuard, RoadmapLookupSupport lookupSupport) {
        this.accessGuard = accessGuard;
        this.lookupSupport = lookupSupport;
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<RoadmapResDto> execute(Long userId, String userRole, Long roadmapId) {
        return lookupSupport.findRoadmapOrThrow(roadmapId)
                .flatMap(roadmap -> accessGuard.requireReadable(roadmap, userId, userRole)
                        .thenReturn(RoadmapResDto.from(roadmap)));
    }
}
