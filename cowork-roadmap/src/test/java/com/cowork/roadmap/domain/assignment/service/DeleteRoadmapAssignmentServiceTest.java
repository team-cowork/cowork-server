package com.cowork.roadmap.domain.assignment.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.impl.DeleteRoadmapAssignmentServiceImpl;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;

import team.themoment.sdk.exception.ExpectedException;

class DeleteRoadmapAssignmentServiceTest {

    private final RoadmapAssignmentRepository assignmentRepository = mock(RoadmapAssignmentRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final DeleteRoadmapAssignmentServiceImpl deleteRoadmapAssignmentService = new DeleteRoadmapAssignmentServiceImpl(
            assignmentRepository,
            accessGuard);

    @Test
    void deleteAssignment_byAssigner_deletesWithoutTeamCheck() {
        RoadmapAssignment assignment = assignment(5L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));

        deleteRoadmapAssignmentService.execute(200L, "MEMBER", 5L);

        verify(assignmentRepository).delete(assignment);
        verify(accessGuard, never()).requireTeamManagerOrAdmin(anyLong(), anyString(), anyLong());
    }

    @Test
    void deleteAssignment_byAdmin_deletesWithoutTeamCheck() {
        RoadmapAssignment assignment = assignment(5L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));

        deleteRoadmapAssignmentService.execute(999L, "ADMIN", 5L);

        verify(assignmentRepository).delete(assignment);
        verify(accessGuard, never()).requireTeamManagerOrAdmin(anyLong(), anyString(), anyLong());
    }

    @Test
    void deleteAssignment_byTeamManager_deletesAfterTeamCheck() {
        RoadmapAssignment assignment = assignment(5L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));

        deleteRoadmapAssignmentService.execute(999L, "MEMBER", 5L);

        verify(accessGuard).requireTeamManagerOrAdmin(999L, "MEMBER", assignment.getTeamId());
        verify(assignmentRepository).delete(assignment);
    }

    @Test
    void deleteAssignment_byUnrelatedNonManager_isForbidden() {
        RoadmapAssignment assignment = assignment(5L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));
        doThrow(new ExpectedException("권한 없음", HttpStatus.FORBIDDEN)).when(accessGuard)
                .requireTeamManagerOrAdmin(anyLong(), anyString(), anyLong());

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> deleteRoadmapAssignmentService.execute(999L, "MEMBER", 5L));
        assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);

        verify(assignmentRepository, never()).delete(any());
    }

    @Test
    void deleteAssignment_assignmentNotFound_failsWithNotFound() {
        when(assignmentRepository.findById(99L)).thenReturn(Optional.empty());

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> deleteRoadmapAssignmentService.execute(1L, "ADMIN", 99L));
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
