package com.cowork.roadmap.domain.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;

import team.themoment.sdk.exception.ExpectedException;

class ModifyRoadmapAssignmentStatusServiceTest {

    private final RoadmapAssignmentRepository assignmentRepository = mock(RoadmapAssignmentRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final ModifyRoadmapAssignmentStatusServiceImpl modifyRoadmapAssignmentStatusService = new ModifyRoadmapAssignmentStatusServiceImpl(
            assignmentRepository,
            accessGuard);

    @Test
    void updateStatus_byUnrelatedNonAdmin_isForbidden() {
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment(5L, 100L, 200L)));

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> modifyRoadmapAssignmentStatusService.execute(999L, "MEMBER", 5L, status(AssignmentStatus.DONE)));
        assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
        verify(accessGuard, never()).requireTeamMember(anyLong(), anyLong());
    }

    @Test
    void updateStatus_byActiveAssignee_updatesStatus() {
        RoadmapAssignment assignment = assignment(5L, 100L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = modifyRoadmapAssignmentStatusService.execute(100L, "MEMBER", 5L, status(AssignmentStatus.DONE));
        assertThat(response.status()).isEqualTo(AssignmentStatus.DONE.name());
        verify(accessGuard).requireTeamMember(5L, 100L);
    }

    @Test
    void updateStatus_byLeftAssigner_isForbidden() {
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment(5L, 100L, 200L)));
        doThrow(new ExpectedException("권한 없음", HttpStatus.FORBIDDEN)).when(accessGuard)
                .requireTeamMember(anyLong(), anyLong());

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> modifyRoadmapAssignmentStatusService.execute(200L, "MEMBER", 5L, status(AssignmentStatus.DONE)));
        assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
        verify(assignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateStatus_byAdmin_updatesWithoutTeamCheck() {
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment(5L, 100L, 200L)));
        when(assignmentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = modifyRoadmapAssignmentStatusService.execute(999L, "ADMIN", 5L, status(AssignmentStatus.DONE));
        assertThat(response.status()).isEqualTo(AssignmentStatus.DONE.name());
        verify(accessGuard, never()).requireTeamMember(anyLong(), anyLong());
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
