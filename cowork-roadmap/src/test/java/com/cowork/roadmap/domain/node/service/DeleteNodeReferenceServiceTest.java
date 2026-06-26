package com.cowork.roadmap.domain.node.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.impl.DeleteNodeReferenceServiceImpl;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class DeleteNodeReferenceServiceImplTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapNodeReferenceRepository referenceRepository = mock(RoadmapNodeReferenceRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final RoadmapNodeLookupSupport nodeLookupSupport = new RoadmapNodeLookupSupport(nodeRepository,
            referenceRepository);
    private final DeleteNodeReferenceServiceImpl deleteNodeReferenceService = new DeleteNodeReferenceServiceImpl(
            referenceRepository,
            accessGuard,
            roadmapLookupSupport,
            nodeLookupSupport);

    @Test
    void deleteReference_whenMutable_deletes() {
        RoadmapNodeReference ref = reference(100L, 5L);
        when(referenceRepository.findById(100L)).thenReturn(Mono.just(ref));
        when(nodeRepository.findById(5L)).thenReturn(Mono.just(node(5L, 10L)));
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(referenceRepository.delete(ref)).thenReturn(Mono.empty());

        StepVerifier.create(deleteNodeReferenceService.execute(7L, "ADMIN", 100L)).verifyComplete();

        verify(referenceRepository).delete(ref);
    }

    @Test
    void deleteReference_referenceNotFound_failsWithNotFound() {
        when(referenceRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(deleteNodeReferenceService.execute(7L, "ADMIN", 99L))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(referenceRepository, never()).delete(any());
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
        ref.setTitle("ref");
        ref.setUrl("https://example.com");
        ref.setPosition(0);
        return ref;
    }
}
