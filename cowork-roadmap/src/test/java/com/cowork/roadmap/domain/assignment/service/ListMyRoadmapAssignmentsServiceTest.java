package com.cowork.roadmap.domain.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.cowork.roadmap.domain.assignment.entity.AssignmentStatus;
import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.impl.ListMyRoadmapAssignmentsServiceImpl;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ListMyRoadmapAssignmentsServiceTest {

    private final RoadmapAssignmentRepository assignmentRepository = mock(RoadmapAssignmentRepository.class);

    private final ListMyRoadmapAssignmentsServiceImpl listMyRoadmapAssignmentsService = new ListMyRoadmapAssignmentsServiceImpl(
            assignmentRepository);

    @Test
    void listMine_returnsAssigneeAssignments() {
        when(assignmentRepository.findByAssigneeUserIdOrderByIdDesc(100L))
                .thenReturn(Flux.just(assignment(1L, 100L), assignment(2L, 100L)));

        StepVerifier.create(listMyRoadmapAssignmentsService.execute(100L)).assertNext(response -> {
            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.assigneeUserId()).isEqualTo(100L);
        }).expectNextCount(1).verifyComplete();
    }

    @Test
    void listMine_whenNone_returnsEmpty() {
        when(assignmentRepository.findByAssigneeUserIdOrderByIdDesc(100L)).thenReturn(Flux.empty());

        StepVerifier.create(listMyRoadmapAssignmentsService.execute(100L)).verifyComplete();
    }

    private static RoadmapAssignment assignment(Long id, Long assigneeUserId) {
        return RoadmapAssignment.builder()
                .id(id)
                .roadmapId(1L)
                .scope(RoadmapScope.TEAM.name())
                .teamId(5L)
                .assigneeUserId(assigneeUserId)
                .assignedBy(200L)
                .status(AssignmentStatus.ASSIGNED.name())
                .build();
    }
}
