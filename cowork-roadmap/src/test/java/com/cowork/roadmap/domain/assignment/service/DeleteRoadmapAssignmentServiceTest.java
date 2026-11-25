package com.cowork.roadmap.domain.assignment.service;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.assignment.entity.AssignmentStatus;
import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.impl.DeleteRoadmapAssignmentServiceImpl;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;

import team.themoment.sdk.exception.ExpectedException;

@DisplayName("DeleteRoadmapAssignmentService 의")
class DeleteRoadmapAssignmentServiceTest {

    private final RoadmapAssignmentRepository assignmentRepository = mock(RoadmapAssignmentRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final DeleteRoadmapAssignmentService deleteRoadmapAssignmentService = new DeleteRoadmapAssignmentServiceImpl(
            assignmentRepository,
            accessGuard);

    @Test
    void deleteAssignment_byActiveAssigner_deletesAfterMembershipCheck() {
        RoadmapAssignment assignment = assignment(5L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));

        deleteRoadmapAssignmentService.execute(200L, 5L);

        verify(accessGuard).requireTeamMember(assignment.getTeamId(), 200L);
        verify(assignmentRepository).delete(assignment);
        verify(accessGuard, never()).requireRoadmapManager(anyLong(), anyLong());
    }

    @Test
    void deleteAssignment_byLeftAssigner_isForbidden() {
        RoadmapAssignment assignment = assignment(5L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));
        doThrow(new ExpectedException("권한 없음", HttpStatus.FORBIDDEN)).when(accessGuard)
                .requireTeamMember(anyLong(), anyLong());

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> deleteRoadmapAssignmentService.execute(200L, 5L));
        assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);

        verify(assignmentRepository, never()).delete(any());
    }

    @Test
    void deleteAssignment_withAccountPermission_deletesWithoutTeamCheck() {
        RoadmapAssignment assignment = assignment(5L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));

        when(accessGuard.accountAllows(999L, "delete_team_assignments")).thenReturn(true);
        deleteRoadmapAssignmentService.execute(999L, 5L);

        verify(assignmentRepository).delete(assignment);
        verify(accessGuard, never()).requireRoadmapManager(anyLong(), anyLong());
        verify(accessGuard, never()).requireTeamMember(anyLong(), anyLong());
    }

    @Test
    void deleteAssignment_byTeamManager_deletesAfterTeamCheck() {
        RoadmapAssignment assignment = assignment(5L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));

        deleteRoadmapAssignmentService.execute(999L, 5L);

        verify(accessGuard).requireRoadmapManager(999L, assignment.getTeamId());
        verify(assignmentRepository).delete(assignment);
    }

    @Test
    void deleteAssignment_byUnrelatedNonManager_isForbidden() {
        RoadmapAssignment assignment = assignment(5L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));
        doThrow(new ExpectedException("권한 없음", HttpStatus.FORBIDDEN)).when(accessGuard)
                .requireRoadmapManager(anyLong(), anyLong());

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> deleteRoadmapAssignmentService.execute(999L, 5L));
        assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);

        verify(assignmentRepository, never()).delete(any());
    }

    @Test
    void deleteAssignment_assignmentNotFound_failsWithNotFound() {
        when(assignmentRepository.findById(99L)).thenReturn(Optional.empty());

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> deleteRoadmapAssignmentService.execute(1L, 99L));
        assertTrue(error.getStatusCode() == HttpStatus.NOT_FOUND);
    }

    private static RoadmapAssignment assignment(Long id, Long assignedBy) {
        return RoadmapAssignment.builder()
                .id(id)
                .roadmapId(1L)
                .scope(RoadmapScope.TEAM.name())
                .teamId(5L)
                .assigneeUserId(100L)
                .assignedBy(assignedBy)
                .status(AssignmentStatus.ASSIGNED.name())
                .build();
    }
}
