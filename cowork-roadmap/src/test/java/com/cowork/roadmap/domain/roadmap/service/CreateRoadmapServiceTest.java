package com.cowork.roadmap.domain.roadmap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.CreateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.CreateRoadmapServiceImpl;

import team.themoment.sdk.exception.ExpectedException;

class CreateRoadmapServiceTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final CreateRoadmapServiceImpl createRoadmapService = new CreateRoadmapServiceImpl(roadmapRepository,
            accessGuard);

    @Nested
    class Execute {

        @Test
        void teamScope_withoutOwnerTeamId_failsWithBadRequest() {
            CreateRoadmapReqDto request = request(RoadmapScope.TEAM, null, null);

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> createRoadmapService.execute(1L, "MEMBER", request));
            assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
        }

        @Test
        void projectScope_failsWithBadRequestWithoutSaving() {
            CreateRoadmapReqDto request = request(RoadmapScope.PROJECT, 5L, 9L);

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> createRoadmapService.execute(1L, "MEMBER", request));
            assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
            verify(roadmapRepository, never()).saveAndFlush(any(Roadmap.class));
        }

        @Test
        void teamScope_withOwnerTeamId_createsTeamRoadmap() {
            CreateRoadmapReqDto request = request(RoadmapScope.TEAM, 5L, 9L);

            when(roadmapRepository.saveAndFlush(any(Roadmap.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var response = createRoadmapService.execute(1L, "MEMBER", request);
            assertEquals("title", response.title());
            assertEquals(RoadmapScope.TEAM.name(), response.scope());
            assertEquals(5L, response.ownerTeamId());
            assertNull(response.ownerProjectId());
            assertEquals(1L, response.createdBy());
            verify(accessGuard).requireCreatable(1L, "MEMBER", RoadmapScope.TEAM, 5L);
        }
    }

    private static CreateRoadmapReqDto request(RoadmapScope scope, Long ownerTeamId, Long ownerProjectId) {
        return new CreateRoadmapReqDto("title", "description", "Flutter", scope, ownerTeamId, ownerProjectId);
    }
}
