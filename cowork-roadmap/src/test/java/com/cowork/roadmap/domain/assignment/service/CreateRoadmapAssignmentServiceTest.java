package com.cowork.roadmap.domain.assignment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

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

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> createRoadmapAssignmentService.execute(1L, "ADMIN", request));
            assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
        }

        @Test
        void globalRoadmap_rejectsEveryTeamAndProjectAssignmentIncludingAdminAndWholeRoadmap() {
            Roadmap roadmap = Roadmap.builder().id(1L).scope(RoadmapScope.GLOBAL.name()).build();
            when(roadmapRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(roadmap));

            when(nodeRepository.findByIdForShare(99L)).thenReturn(Optional.of(node(99L, 1L)));
            when(assignmentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

            for (String role : new String[]{"ADMIN", "MEMBER"}) {
                for (RoadmapScope scope : new RoadmapScope[]{RoadmapScope.TEAM, RoadmapScope.PROJECT}) {
                    for (Long nodeId : new Long[]{null, 99L}) {
                        ExpectedException error = assertThrows(ExpectedException.class,
                                () -> createRoadmapAssignmentService.execute(1L,
                                        role,
                                        request(scope, scope == RoadmapScope.PROJECT ? 9L : null, nodeId)));
                        assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
                    }
                }
            }
            verify(assignmentRepository, never()).saveAndFlush(any());
        }

        @Test
        void projectScope_withoutProjectId_failsWithBadRequest() {
            CreateAssignmentReqDto request = request(RoadmapScope.PROJECT, null, null);

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> createRoadmapAssignmentService.execute(1L, "ADMIN", request));
            assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
        }

        @Test
        void missingNode_failsWithNotFound() {
            CreateAssignmentReqDto request = request(RoadmapScope.TEAM, null, 99L);
            prepareAuthorizedRoadmap();
            when(nodeRepository.findByIdForShare(99L)).thenReturn(Optional.empty());

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> createRoadmapAssignmentService.execute(1L, "MEMBER", request));
            assertTrue(error.getStatusCode() == HttpStatus.NOT_FOUND);
        }

        @Test
        void nodeFromDifferentRoadmap_failsWithBadRequest() {
            CreateAssignmentReqDto request = request(RoadmapScope.TEAM, null, 99L);
            prepareAuthorizedRoadmap();
            when(nodeRepository.findByIdForShare(99L)).thenReturn(Optional.of(node(99L, 2L)));

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> createRoadmapAssignmentService.execute(1L, "MEMBER", request));
            assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
        }

        @Test
        void authorizedProjectAssignment_storesInitialBusinessState() {
            CreateAssignmentReqDto request = request(RoadmapScope.PROJECT, 9L, 99L);
            prepareAuthorizedRoadmap();
            when(nodeRepository.findByIdForShare(99L)).thenReturn(Optional.of(node(99L, 1L)));
            when(assignmentRepository.saveAndFlush(any(RoadmapAssignment.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var response = createRoadmapAssignmentService.execute(1L, "MEMBER", request);
            assertEquals(1L, response.roadmapId());
            assertEquals(99L, response.nodeId());
            assertEquals(RoadmapScope.PROJECT.name(), response.scope());
            assertEquals(5L, response.teamId());
            assertEquals(9L, response.projectId());
            assertEquals(42L, response.assigneeUserId());
            assertEquals(1L, response.assignedBy());
            assertEquals(AssignmentStatus.ASSIGNED.name(), response.status());
        }
    }

    private void prepareAuthorizedRoadmap() {
        Roadmap roadmap = Roadmap.builder().id(1L).scope(RoadmapScope.TEAM.name()).ownerTeamId(5L).build();
        when(roadmapRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(roadmap));

    }

    private static CreateAssignmentReqDto request(RoadmapScope scope, Long projectId, Long nodeId) {
        return new CreateAssignmentReqDto(1L, nodeId, scope, 5L, projectId, 42L, null);
    }

    private static RoadmapNode node(Long id, Long roadmapId) {
        return RoadmapNode.builder().id(id).roadmapId(roadmapId).build();
    }
}
