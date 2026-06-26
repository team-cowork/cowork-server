package com.cowork.roadmap.domain.roadmap.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.DeleteRoadmapServiceImpl;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class DeleteRoadmapServiceImplTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport lookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final DeleteRoadmapServiceImpl deleteRoadmapService = new DeleteRoadmapServiceImpl(roadmapRepository,
            accessGuard,
            lookupSupport);

    @Test
    void deleteRoadmap_whenMutable_deletes() {
        Roadmap roadmap = roadmap(10L);
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(roadmapRepository.delete(roadmap)).thenReturn(Mono.empty());

        StepVerifier.create(deleteRoadmapService.execute(1L, "ADMIN", 10L)).verifyComplete();

        verify(roadmapRepository).delete(roadmap);
    }

    @Test
    void deleteRoadmap_roadmapNotFound_failsWithNotFound() {
        when(roadmapRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(deleteRoadmapService.execute(1L, "ADMIN", 99L))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(roadmapRepository, never()).delete(any());
    }

    @Test
    void deleteRoadmap_whenNotMutable_doesNotDelete() {
        Roadmap roadmap = roadmap(10L);
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap));
        when(accessGuard.requireMutable(any(), anyLong(), anyString()))
                .thenReturn(Mono.error(new ExpectedException("권한 없음", HttpStatus.FORBIDDEN)));
        // requireMutable().then(delete(roadmap)) 구조라 delete 인자가 eager 평가된다. NPE 방지를 위해
        // stub.
        when(roadmapRepository.delete(roadmap)).thenReturn(Mono.empty());

        StepVerifier.create(deleteRoadmapService.execute(2L, "MEMBER", 10L))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    private static Roadmap roadmap(Long id) {
        Roadmap roadmap = new Roadmap();
        roadmap.setId(id);
        roadmap.setScope(RoadmapScope.GLOBAL.name());
        return roadmap;
    }
}
