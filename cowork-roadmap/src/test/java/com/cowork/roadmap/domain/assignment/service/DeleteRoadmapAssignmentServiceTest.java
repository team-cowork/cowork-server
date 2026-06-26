package com.cowork.roadmap.domain.assignment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.assignment.entity.AssignmentStatus;
import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.impl.DeleteRoadmapAssignmentServiceImpl;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
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
        when(assignmentRepository.findById(5L)).thenReturn(Mono.just(assignment));
        when(assignmentRepository.delete(assignment)).thenReturn(Mono.empty());

        StepVerifier.create(deleteRoadmapAssignmentService.execute(200L, "MEMBER", 5L)).verifyComplete();

        verify(assignmentRepository).delete(assignment);
        verify(accessGuard, never()).requireTeamManagerOrAdmin(anyLong(), anyString(), anyLong());
    }

    @Test
    void deleteAssignment_byAdmin_deletesWithoutTeamCheck() {
        RoadmapAssignment assignment = assignment(5L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Mono.just(assignment));
        when(assignmentRepository.delete(assignment)).thenReturn(Mono.empty());

        StepVerifier.create(deleteRoadmapAssignmentService.execute(999L, "ADMIN", 5L)).verifyComplete();

        verify(assignmentRepository).delete(assignment);
        verify(accessGuard, never()).requireTeamManagerOrAdmin(anyLong(), anyString(), anyLong());
    }

    @Test
    void deleteAssignment_byTeamManager_deletesAfterTeamCheck() {
        RoadmapAssignment assignment = assignment(5L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Mono.just(assignment));
        when(accessGuard.requireTeamManagerOrAdmin(999L, "MEMBER", assignment.getTeamId())).thenReturn(Mono.empty());
        when(assignmentRepository.delete(assignment)).thenReturn(Mono.empty());

        StepVerifier.create(deleteRoadmapAssignmentService.execute(999L, "MEMBER", 5L)).verifyComplete();

        verify(accessGuard).requireTeamManagerOrAdmin(999L, "MEMBER", assignment.getTeamId());
        verify(assignmentRepository).delete(assignment);
    }

    @Test
    void deleteAssignment_byUnrelatedNonManager_isForbidden() {
        RoadmapAssignment assignment = assignment(5L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Mono.just(assignment));
        when(accessGuard.requireTeamManagerOrAdmin(anyLong(), anyString(), anyLong()))
                .thenReturn(Mono.error(new ExpectedException("권한 없음", HttpStatus.FORBIDDEN)));

        StepVerifier.create(deleteRoadmapAssignmentService.execute(999L, "MEMBER", 5L))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();

        // delete는 Mono.defer로 감싸져 권한 검증 실패 시 호출되지 않는다.
        verify(assignmentRepository, never()).delete(any());
    }

    @Test
    void deleteAssignment_assignmentNotFound_failsWithNotFound() {
        when(assignmentRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(deleteRoadmapAssignmentService.execute(1L, "ADMIN", 99L))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    private static RoadmapAssignment assignment(Long id, Long assignedBy) {
        RoadmapAssignment assignment = new RoadmapAssignment();
        assignment.setId(id);
        assignment.setRoadmapId(1L);
        assignment.setScope(RoadmapScope.TEAM.name());
        assignment.setTeamId(5L);
        assignment.setAssigneeUserId(100L);
        assignment.setAssignedBy(assignedBy);
        assignment.setStatus(AssignmentStatus.ASSIGNED.name());
        return assignment;
    }
}
