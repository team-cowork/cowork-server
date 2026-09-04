package com.cowork.roadmap.domain.assignment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.assignment.entity.AssignmentStatus;
import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.presentation.data.request.CreateAssignmentReqDto;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.impl.CreateRoadmapAssignmentServiceImpl;
import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class CreateRoadmapAssignmentServiceTest {

    private final RoadmapAssignmentRepository assignmentRepository = mock(RoadmapAssignmentRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final CreateRoadmapAssignmentServiceImpl createRoadmapAssignmentService = new CreateRoadmapAssignmentServiceImpl(
            assignmentRepository,
            nodeRepository,
            accessGuard,
            roadmapLookupSupport);

    @Nested
    class Execute {

        @Test
        void globalAssignmentScope_failsWithBadRequest() {
            CreateAssignmentReqDto request = request(RoadmapScope.GLOBAL, null, null);

            StepVerifier.create(createRoadmapAssignmentService.execute(1L, "ADMIN", request))
                    .expectErrorMatches(error -> error instanceof ExpectedException expected
                            && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
                    .verify();
        }

        @Test
        void projectScope_withoutProjectId_failsWithBadRequest() {
            CreateAssignmentReqDto request = request(RoadmapScope.PROJECT, null, null);

            StepVerifier.create(createRoadmapAssignmentService.execute(1L, "ADMIN", request))
                    .expectErrorMatches(error -> error instanceof ExpectedException expected
                            && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
                    .verify();
        }

        @Test
        void missingNode_failsWithNotFound() {
            CreateAssignmentReqDto request = request(RoadmapScope.TEAM, null, 99L);
            prepareAuthorizedRoadmap();
            when(nodeRepository.findById(99L)).thenReturn(Mono.empty());

            StepVerifier.create(createRoadmapAssignmentService.execute(1L, "MEMBER", request))
                    .expectErrorMatches(error -> error instanceof ExpectedException expected
                            && expected.getStatusCode() == HttpStatus.NOT_FOUND)
                    .verify();
        }

        @Test
        void nodeFromDifferentRoadmap_failsWithBadRequest() {
            CreateAssignmentReqDto request = request(RoadmapScope.TEAM, null, 99L);
            prepareAuthorizedRoadmap();
            when(nodeRepository.findById(99L)).thenReturn(Mono.just(node(99L, 2L)));

            StepVerifier.create(createRoadmapAssignmentService.execute(1L, "MEMBER", request))
                    .expectErrorMatches(error -> error instanceof ExpectedException expected
                            && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
                    .verify();
        }

        @Test
        void authorizedProjectAssignment_storesInitialBusinessState() {
            CreateAssignmentReqDto request = request(RoadmapScope.PROJECT, 9L, 99L);
            prepareAuthorizedRoadmap();
            when(nodeRepository.findById(99L)).thenReturn(Mono.just(node(99L, 1L)));
            when(assignmentRepository.save(any(RoadmapAssignment.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(createRoadmapAssignmentService.execute(1L, "MEMBER", request)).assertNext(response -> {
                assertEquals(1L, response.roadmapId());
                assertEquals(99L, response.nodeId());
                assertEquals(RoadmapScope.PROJECT.name(), response.scope());
                assertEquals(5L, response.teamId());
                assertEquals(9L, response.projectId());
                assertEquals(42L, response.assigneeUserId());
                assertEquals(1L, response.assignedBy());
                assertEquals(AssignmentStatus.ASSIGNED.name(), response.status());
            }).verifyComplete();
        }
    }

    private void prepareAuthorizedRoadmap() {
        Roadmap roadmap = Roadmap.builder().id(1L).scope(RoadmapScope.TEAM.name()).ownerTeamId(5L).build();
        when(roadmapRepository.findById(1L)).thenReturn(Mono.just(roadmap));
        when(accessGuard.requireReadable(any(), any(), any())).thenReturn(Mono.empty());
        when(accessGuard.requireTeamManagerOrAdmin(any(), any(), any())).thenReturn(Mono.empty());
    }

    private static CreateAssignmentReqDto request(RoadmapScope scope, Long projectId, Long nodeId) {
        return new CreateAssignmentReqDto(1L, nodeId, scope, 5L, projectId, 42L, null);
    }

    private static RoadmapNode node(Long id, Long roadmapId) {
        return RoadmapNode.builder().id(id).roadmapId(roadmapId).build();
    }
}
