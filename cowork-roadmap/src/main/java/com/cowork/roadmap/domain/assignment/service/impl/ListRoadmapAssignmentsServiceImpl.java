package com.cowork.roadmap.domain.assignment.service.impl;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.ListRoadmapAssignmentsService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class ListRoadmapAssignmentsServiceImpl implements ListRoadmapAssignmentsService {

    private final RoadmapAssignmentRepository assignmentRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;

    @Override
    @Transactional(readOnly = true)
    public Flux<AssignmentResDto> execute(Long userId, String userRole, Long roadmapId) {
        return roadmapLookupSupport.findRoadmapOrThrow(roadmapId)
                .flatMapMany(
                        roadmap -> accessGuard.requireReadable(roadmap, userId, userRole).thenMany(Flux.defer(() -> {
                            Map<Long, Mono<Boolean>> readableTeams = new HashMap<>();
                            return assignmentRepository.findByRoadmapIdOrderByIdDesc(roadmapId)
                                    .concatMap(
                                            assignment -> readableTeams
                                                    .computeIfAbsent(assignment.getTeamId(),
                                                            teamId -> accessGuard.canReadTeam(teamId, userId, userRole)
                                                                    .cache())
                                                    .filter(Boolean::booleanValue)
                                                    .map(ignored -> AssignmentResDto.from(assignment)));
                        })));
    }
}
