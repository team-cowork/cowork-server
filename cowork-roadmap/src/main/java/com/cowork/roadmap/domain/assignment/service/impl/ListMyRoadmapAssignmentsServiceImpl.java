package com.cowork.roadmap.domain.assignment.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.ListMyRoadmapAssignmentsService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ListMyRoadmapAssignmentsServiceImpl implements ListMyRoadmapAssignmentsService {

    private final RoadmapAssignmentRepository assignmentRepository;

    @Override
    @Transactional(readOnly = true)
    public List<AssignmentResDto> execute(Long userId) {
        return assignmentRepository.findByAssigneeUserIdOrderByIdDesc(userId)
                .stream()
                .map(AssignmentResDto::from)
                .toList();
    }
}
