package com.cowork.roadmap.domain.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.assignment.entity.AssignmentStatus;
import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.presentation.data.request.CreateAssignmentRequest;
import com.cowork.roadmap.domain.assignment.presentation.data.request.UpdateAssignmentStatusRequest;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class RoadmapAssignmentServiceTest {

    private final RoadmapAssignmentRepository assignmentRepository = mock(RoadmapAssignmentRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapAssignmentService assignmentService = new RoadmapAssignmentService(assignmentRepository,
            roadmapRepository,
            accessGuard);

    @Test
    void createAssignment_globalScope_failsWithBadRequest() {
        CreateAssignmentRequest request = new CreateAssignmentRequest(1L,
                null,
                RoadmapScope.GLOBAL,
                5L,
                null,
                42L,
                null);

        StepVerifier.create(assignmentService.createAssignment(1L, "ADMIN", request))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    void updateStatus_byUnrelatedNonAdmin_isForbidden() {
        when(assignmentRepository.findById(5L)).thenReturn(Mono.just(assignment(5L, 100L, 200L)));

        StepVerifier.create(assignmentService.updateStatus(999L, "MEMBER", 5L, status(AssignmentStatus.DONE)))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void updateStatus_byAssignee_updatesStatus() {
        RoadmapAssignment assignment = assignment(5L, 100L, 200L);
        when(assignmentRepository.findById(5L)).thenReturn(Mono.just(assignment));
        when(assignmentRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(assignmentService.updateStatus(100L, "MEMBER", 5L, status(AssignmentStatus.DONE)))
                .assertNext(response -> assertThat(response.status()).isEqualTo(AssignmentStatus.DONE.name()))
                .verifyComplete();
    }

    private static UpdateAssignmentStatusRequest status(AssignmentStatus status) {
        return new UpdateAssignmentStatusRequest(status);
    }

    private static RoadmapAssignment assignment(Long id, Long assigneeUserId, Long assignedBy) {
        RoadmapAssignment assignment = new RoadmapAssignment();
        assignment.setId(id);
        assignment.setRoadmapId(1L);
        assignment.setScope(RoadmapScope.TEAM.name());
        assignment.setTeamId(5L);
        assignment.setAssigneeUserId(assigneeUserId);
        assignment.setAssignedBy(assignedBy);
        assignment.setStatus(AssignmentStatus.ASSIGNED.name());
        return assignment;
    }
}
