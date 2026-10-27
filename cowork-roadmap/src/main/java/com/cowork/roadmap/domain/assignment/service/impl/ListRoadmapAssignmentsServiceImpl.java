package com.cowork.roadmap.domain.assignment.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.ListRoadmapAssignmentsService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ListRoadmapAssignmentsServiceImpl implements ListRoadmapAssignmentsService {

    private final RoadmapAssignmentRepository assignmentRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;

    @Override
    @Transactional(readOnly = true)
    public List<AssignmentResDto> execute(Long userId, String userRole, Long roadmapId) {
        var roadmap = roadmapLookupSupport.findRoadmapOrThrow(roadmapId);
        accessGuard.requireReadable(roadmap, userId, userRole);
        Map<Long, Boolean> readableTeams = new HashMap<>();
        return assignmentRepository.findByRoadmapIdOrderByIdDesc(roadmapId)
                .stream()
                .filter(assignment -> readableTeams.computeIfAbsent(assignment.getTeamId(),
                        teamId -> accessGuard.canReadTeam(teamId, userId, userRole)))
                .map(AssignmentResDto::from)
                .toList();
    }
}
