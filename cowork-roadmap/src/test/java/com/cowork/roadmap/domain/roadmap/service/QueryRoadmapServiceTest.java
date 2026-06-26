package com.cowork.roadmap.domain.roadmap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.QueryRoadmapServiceImpl;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class QueryRoadmapServiceTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport lookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final QueryRoadmapServiceImpl queryRoadmapService = new QueryRoadmapServiceImpl(accessGuard, lookupSupport);

    @Test
    void queryRoadmap_whenReadable_returnsRoadmap() {
        Roadmap roadmap = roadmap(10L);
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap));
        when(accessGuard.requireReadable(any(), anyLong(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(queryRoadmapService.execute(1L, "MEMBER", 10L))
                .assertNext(response -> assertThat(response.id()).isEqualTo(10L))
                .verifyComplete();
    }

    @Test
    void queryRoadmap_roadmapNotFound_failsWithNotFound() {
        when(roadmapRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(queryRoadmapService.execute(1L, "MEMBER", 99L))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void queryRoadmap_whenNotReadable_isForbidden() {
        Roadmap roadmap = roadmap(10L);
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap));
        when(accessGuard.requireReadable(any(), anyLong(), anyString()))
                .thenReturn(Mono.error(new ExpectedException("권한 없음", HttpStatus.FORBIDDEN)));

        StepVerifier.create(queryRoadmapService.execute(2L, "MEMBER", 10L))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    private static Roadmap roadmap(Long id) {
        Roadmap roadmap = new Roadmap();
        roadmap.setId(id);
        roadmap.setScope(RoadmapScope.TEAM.name());
        roadmap.setOwnerTeamId(5L);
        return roadmap;
    }
}
