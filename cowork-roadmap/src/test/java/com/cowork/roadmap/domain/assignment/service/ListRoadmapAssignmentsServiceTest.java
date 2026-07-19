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
import com.cowork.roadmap.domain.assignment.service.impl.ListRoadmapAssignmentsServiceImpl;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class ListRoadmapAssignmentsServiceTest {

    private final RoadmapAssignmentRepository assignmentRepository = mock(RoadmapAssignmentRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final ListRoadmapAssignmentsServiceImpl listRoadmapAssignmentsService = new ListRoadmapAssignmentsServiceImpl(
            assignmentRepository,
            accessGuard,
            roadmapLookupSupport);

    @Test
    void listByRoadmap_whenReadable_returnsAssignments() {
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireReadable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(assignmentRepository.findByRoadmapIdOrderByIdDesc(10L))
                .thenReturn(Flux.just(assignment(1L), assignment(2L)));

        StepVerifier.create(listRoadmapAssignmentsService.execute(1L, "MEMBER", 10L))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void listByRoadmap_roadmapNotFound_failsWithNotFound() {
        when(roadmapRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(listRoadmapAssignmentsService.execute(1L, "MEMBER", 99L))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void listByRoadmap_whenNotReadable_isForbidden() {
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireReadable(any(), anyLong(), anyString()))
                .thenReturn(Mono.error(new ExpectedException("권한 없음", HttpStatus.FORBIDDEN)));

        StepVerifier.create(listRoadmapAssignmentsService.execute(2L, "MEMBER", 10L))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();

        // find는 Flux.defer로 감싸져 권한 검증 실패 시 호출되지 않는다.
        verify(assignmentRepository, never()).findByRoadmapIdOrderByIdDesc(anyLong());
    }

    private static Roadmap roadmap(Long id) {
        Roadmap roadmap = new Roadmap();
        roadmap.setId(id);
        roadmap.setScope(RoadmapScope.TEAM.name());
        roadmap.setOwnerTeamId(5L);
        return roadmap;
    }

    private static RoadmapAssignment assignment(Long id) {
        RoadmapAssignment assignment = new RoadmapAssignment();
        assignment.setId(id);
        assignment.setRoadmapId(10L);
        assignment.setScope(RoadmapScope.TEAM.name());
        assignment.setTeamId(5L);
        assignment.setAssigneeUserId(100L);
        assignment.setAssignedBy(200L);
        assignment.setStatus(AssignmentStatus.ASSIGNED.name());
        return assignment;
    }
}
