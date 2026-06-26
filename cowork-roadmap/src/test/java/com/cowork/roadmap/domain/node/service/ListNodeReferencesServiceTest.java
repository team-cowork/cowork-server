package com.cowork.roadmap.domain.node.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.impl.ListNodeReferencesServiceImpl;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class ListNodeReferencesServiceTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapNodeReferenceRepository referenceRepository = mock(RoadmapNodeReferenceRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final RoadmapNodeLookupSupport nodeLookupSupport = new RoadmapNodeLookupSupport(nodeRepository,
            referenceRepository);
    private final ListNodeReferencesServiceImpl listNodeReferencesService = new ListNodeReferencesServiceImpl(
            referenceRepository,
            accessGuard,
            roadmapLookupSupport,
            nodeLookupSupport);

    @Test
    void listReferences_whenReadable_returnsReferences() {
        when(nodeRepository.findById(5L)).thenReturn(Mono.just(node(5L, 10L)));
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireReadable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(referenceRepository.findByNodeIdOrderByPositionAsc(5L))
                .thenReturn(Flux.just(reference(100L, 5L), reference(101L, 5L)));

        StepVerifier.create(listNodeReferencesService.execute(1L, "MEMBER", 5L)).expectNextCount(2).verifyComplete();
    }

    @Test
    void listReferences_nodeNotFound_failsWithNotFound() {
        when(nodeRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(listNodeReferencesService.execute(1L, "MEMBER", 99L))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    private static Roadmap roadmap(Long id) {
        Roadmap roadmap = new Roadmap();
        roadmap.setId(id);
        roadmap.setScope(RoadmapScope.GLOBAL.name());
        return roadmap;
    }

    private static RoadmapNode node(Long id, Long roadmapId) {
        RoadmapNode node = new RoadmapNode();
        node.setId(id);
        node.setRoadmapId(roadmapId);
        return node;
    }

    private static RoadmapNodeReference reference(Long id, Long nodeId) {
        RoadmapNodeReference ref = new RoadmapNodeReference();
        ref.setId(id);
        ref.setNodeId(nodeId);
        ref.setTitle("ref" + id);
        ref.setUrl("https://example.com");
        ref.setPosition(0);
        return ref;
    }
}
