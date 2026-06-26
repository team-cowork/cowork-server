package com.cowork.roadmap.domain.assignment.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.ListRoadmapAssignmentsService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Flux;

@Service
public class ListRoadmapAssignmentsServiceImpl implements ListRoadmapAssignmentsService {

    private final RoadmapAssignmentRepository assignmentRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;

    public ListRoadmapAssignmentsServiceImpl(RoadmapAssignmentRepository assignmentRepository,
            RoadmapAccessGuard accessGuard,
            RoadmapLookupSupport roadmapLookupSupport) {
        this.assignmentRepository = assignmentRepository;
        this.accessGuard = accessGuard;
        this.roadmapLookupSupport = roadmapLookupSupport;
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<AssignmentResDto> execute(Long userId, String userRole, Long roadmapId) {
        return roadmapLookupSupport.findRoadmapOrThrow(roadmapId)
                .flatMapMany(roadmap -> accessGuard.requireReadable(roadmap, userId, userRole)
                        .thenMany(Flux.defer(() -> assignmentRepository.findByRoadmapIdOrderByIdDesc(roadmapId)
                                .map(AssignmentResDto::from))));
    }
}
