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
import com.cowork.roadmap.domain.node.presentation.data.request.UpdateNodeReqDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.impl.ModifyRoadmapNodeServiceImpl;
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

class ModifyRoadmapNodeServiceImplTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapNodeReferenceRepository referenceRepository = mock(RoadmapNodeReferenceRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final RoadmapNodeLookupSupport nodeLookupSupport = new RoadmapNodeLookupSupport(nodeRepository,
            referenceRepository);
    private final ModifyRoadmapNodeServiceImpl modifyRoadmapNodeService = new ModifyRoadmapNodeServiceImpl(
            nodeRepository,
            accessGuard,
            roadmapLookupSupport,
            nodeLookupSupport);

    @Test
    void modifyNode_updatesOnlyNonNullFields() {
        RoadmapNode node = node(5L, 10L, "원제목", "원내용");
        when(nodeRepository.findById(5L)).thenReturn(Mono.just(node));
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(nodeRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(referenceRepository.findByNodeIdOrderByPositionAsc(5L)).thenReturn(Flux.empty());

        UpdateNodeReqDto request = new UpdateNodeReqDto("새제목", null, null, null);

        StepVerifier.create(modifyRoadmapNodeService.execute(7L, "ADMIN", 5L, request)).assertNext(response -> {
            assertThat(response.title()).isEqualTo("새제목");
            assertThat(response.content()).isEqualTo("원내용");
        }).verifyComplete();

        assertThat(node.getLastModifiedBy()).isEqualTo(7L);
    }

    @Test
    void modifyNode_nodeNotFound_failsWithNotFound() {
        when(nodeRepository.findById(99L)).thenReturn(Mono.empty());

        UpdateNodeReqDto request = new UpdateNodeReqDto("새제목", null, null, null);

        StepVerifier.create(modifyRoadmapNodeService.execute(7L, "ADMIN", 99L, request))
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

    private static RoadmapNode node(Long id, Long roadmapId, String title, String content) {
        RoadmapNode node = new RoadmapNode();
        node.setId(id);
        node.setRoadmapId(roadmapId);
        node.setTitle(title);
        node.setContent(content);
        node.setPosition(0);
        return node;
    }
}
