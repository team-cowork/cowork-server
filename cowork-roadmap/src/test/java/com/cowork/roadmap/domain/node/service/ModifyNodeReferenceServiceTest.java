package com.cowork.roadmap.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.presentation.data.request.NodeReferenceReqDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.impl.ModifyNodeReferenceServiceImpl;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class ModifyNodeReferenceServiceTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapNodeReferenceRepository referenceRepository = mock(RoadmapNodeReferenceRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final RoadmapNodeLookupSupport nodeLookupSupport = new RoadmapNodeLookupSupport(nodeRepository,
            referenceRepository);
    private final ModifyNodeReferenceServiceImpl modifyNodeReferenceService = new ModifyNodeReferenceServiceImpl(
            referenceRepository,
            accessGuard,
            roadmapLookupSupport,
            nodeLookupSupport);

    @Test
    void modifyReference_overwritesTitleAndUrl() {
        RoadmapNodeReference ref = reference(100L, 5L, "원제목", "https://old.com");
        when(referenceRepository.findById(100L)).thenReturn(Mono.just(ref));
        when(nodeRepository.findById(5L)).thenReturn(Mono.just(node(5L, 10L)));
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(referenceRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        NodeReferenceReqDto request = new NodeReferenceReqDto("새제목", "https://new.com");

        StepVerifier.create(modifyNodeReferenceService.execute(7L, "ADMIN", 100L, request)).assertNext(response -> {
            assertThat(response.title()).isEqualTo("새제목");
            assertThat(response.url()).isEqualTo("https://new.com");
        }).verifyComplete();
    }

    @Test
    void modifyReference_referenceNotFound_failsWithNotFound() {
        when(referenceRepository.findById(99L)).thenReturn(Mono.empty());

        NodeReferenceReqDto request = new NodeReferenceReqDto("새제목", "https://new.com");

        StepVerifier.create(modifyNodeReferenceService.execute(7L, "ADMIN", 99L, request))
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

    private static RoadmapNodeReference reference(Long id, Long nodeId, String title, String url) {
        RoadmapNodeReference ref = new RoadmapNodeReference();
        ref.setId(id);
        ref.setNodeId(nodeId);
        ref.setTitle(title);
        ref.setUrl(url);
        ref.setPosition(0);
        return ref;
    }
}
