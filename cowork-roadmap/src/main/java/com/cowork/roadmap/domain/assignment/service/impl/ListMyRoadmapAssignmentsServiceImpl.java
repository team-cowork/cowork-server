package com.cowork.roadmap.domain.assignment.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.ListMyRoadmapAssignmentsService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class ListMyRoadmapAssignmentsServiceImpl implements ListMyRoadmapAssignmentsService {

    private final RoadmapAssignmentRepository assignmentRepository;

    @Override
    @Transactional(readOnly = true)
    public Flux<AssignmentResDto> execute(Long userId) {
        return assignmentRepository.findByAssigneeUserIdOrderByIdDesc(userId).map(AssignmentResDto::from);
    }
}
