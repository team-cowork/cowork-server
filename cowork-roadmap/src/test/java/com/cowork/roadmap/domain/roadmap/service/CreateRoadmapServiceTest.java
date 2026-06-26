package com.cowork.roadmap.domain.roadmap.service;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.CreateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.CreateRoadmapServiceImpl;

import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class CreateRoadmapServiceTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final CreateRoadmapServiceImpl createRoadmapService = new CreateRoadmapServiceImpl(roadmapRepository,
            accessGuard);

    @Test
    void createRoadmap_teamScopeWithoutOwnerTeamId_failsWithBadRequest() {
        CreateRoadmapReqDto request = new CreateRoadmapReqDto("title", null, "Flutter", RoadmapScope.TEAM, null, null);

        StepVerifier.create(createRoadmapService.execute(1L, "MEMBER", request))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }
}
