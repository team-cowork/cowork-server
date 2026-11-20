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

    private final RoadmapAssignmentRepository roadmapAssignmentRepository;
    private final RoadmapAccessGuard roadmapAccessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;

    @Override
    @Transactional(readOnly = true)
    public List<AssignmentResDto> execute(Long userId, Long roadmapId) {
        var roadmap = roadmapLookupSupport.findRoadmapOrThrow(roadmapId);
        roadmapAccessGuard.requireReadable(roadmap, userId);
        Map<Long, Boolean> readableTeams = new HashMap<>();
        return roadmapAssignmentRepository.findByRoadmapIdOrderByIdDesc(roadmapId)
                .stream()
                .filter(assignment -> readableTeams.computeIfAbsent(assignment.getTeamId(),
                        teamId -> roadmapAccessGuard.canReadTeam(teamId, userId)))
                .map(AssignmentResDto::from)
                .toList();
    }
}
