package com.cowork.roadmap.domain.roadmap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
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

            StepVerifier.create(createRoadmapService.execute(1L, "MEMBER", request))
                    .expectErrorMatches(error -> error instanceof ExpectedException expected
                            && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
                    .verify();
        }

        @Test
        void projectScope_withoutOwnerProjectId_failsWithBadRequest() {
            CreateRoadmapReqDto request = request(RoadmapScope.PROJECT, 5L, null);

            StepVerifier.create(createRoadmapService.execute(1L, "MEMBER", request))
                    .expectErrorMatches(error -> error instanceof ExpectedException expected
                            && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
                    .verify();
        }

        @Test
        void projectScope_withRequiredOwners_createsScopedRoadmap() {
            CreateRoadmapReqDto request = request(RoadmapScope.PROJECT, 5L, 9L);
            when(accessGuard.requireCreatable(1L, "MEMBER", RoadmapScope.PROJECT, 5L)).thenReturn(Mono.empty());
            when(roadmapRepository.save(any(Roadmap.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(createRoadmapService.execute(1L, "MEMBER", request)).assertNext(response -> {
                assertEquals("title", response.title());
                assertEquals(RoadmapScope.PROJECT.name(), response.scope());
                assertEquals(5L, response.ownerTeamId());
                assertEquals(9L, response.ownerProjectId());
                assertEquals(1L, response.createdBy());
            }).verifyComplete();
            verify(accessGuard).requireCreatable(1L, "MEMBER", RoadmapScope.PROJECT, 5L);
        }
    }

    private static CreateRoadmapReqDto request(RoadmapScope scope, Long ownerTeamId, Long ownerProjectId) {
        return new CreateRoadmapReqDto("title", "description", "Flutter", scope, ownerTeamId, ownerProjectId);
    }
}
