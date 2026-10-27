package com.cowork.roadmap.domain.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.assignment.entity.AssignmentStatus;
import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.presentation.data.request.UpdateAssignmentStatusReqDto;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.impl.ModifyRoadmapAssignmentStatusServiceImpl;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;

import team.themoment.sdk.exception.ExpectedException;

class ModifyRoadmapAssignmentStatusServiceTest {

    private final RoadmapAssignmentRepository assignmentRepository = mock(RoadmapAssignmentRepository.class);

    private final ModifyRoadmapAssignmentStatusServiceImpl modifyRoadmapAssignmentStatusService = new ModifyRoadmapAssignmentStatusServiceImpl(
            assignmentRepository);

    @Test
    void updateStatus_byUnrelatedNonAdmin_isForbidden() {
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment(5L, 100L, 200L)));

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> modifyRoadmapAssignmentStatusService.execute(999L, "MEMBER", 5L, status(AssignmentStatus.DONE)));
        assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
    }

    @Test
    void updateStatus_byAssignee_updatesStatus() {
        RoadmapAssignment assignment = assignment(5L, 100L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = modifyRoadmapAssignmentStatusService.execute(100L, "MEMBER", 5L, status(AssignmentStatus.DONE));
        assertThat(response.status()).isEqualTo(AssignmentStatus.DONE.name());
    }

    private static UpdateAssignmentStatusReqDto status(AssignmentStatus status) {
        return new UpdateAssignmentStatusReqDto(status);
    }

    private static RoadmapAssignment assignment(Long id, Long assigneeUserId, Long assignedBy) {
        return RoadmapAssignment.builder()
                .id(id)
                .roadmapId(1L)
                .scope(RoadmapScope.TEAM.name())
                .teamId(5L)
                .assigneeUserId(assigneeUserId)
                .assignedBy(assignedBy)
                .status(AssignmentStatus.ASSIGNED.name())
                .build();
    }
}
