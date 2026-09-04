package com.cowork.roadmap.domain.node.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.presentation.data.request.ReorderNodesReqDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.impl.ReorderRoadmapNodesServiceImpl;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class ReorderRoadmapNodesServiceTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final ReorderRoadmapNodesServiceImpl reorderRoadmapNodesService = new ReorderRoadmapNodesServiceImpl(
            nodeRepository,
            accessGuard,
            roadmapLookupSupport);

    @Nested
    class Execute {

        @Test
        void siblingIdsDoNotMatch_failsWithBadRequest() {
            prepareNodes(List.of(node(1L, 10L, null), node(2L, 10L, null)));
            ReorderNodesReqDto request = new ReorderNodesReqDto(null, List.of(1L, 2L, 3L));

            StepVerifier.create(reorderRoadmapNodesService.execute(1L, "ADMIN", 10L, request))
                    .expectErrorMatches(error -> error instanceof ExpectedException expected
                            && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
                    .verify();
        }

        @Test
        void duplicateIds_failWithBadRequest() {
            prepareNodes(List.of(node(1L, 10L, null), node(2L, 10L, null)));
            ReorderNodesReqDto request = new ReorderNodesReqDto(null, List.of(1L, 1L));

            StepVerifier.create(reorderRoadmapNodesService.execute(1L, "ADMIN", 10L, request))
                    .expectErrorMatches(error -> error instanceof ExpectedException expected
                            && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
                    .verify();
        }

        @Test
        void validSiblingOrder_updatesOnlyRequestedLevel() {
            RoadmapNode first = node(1L, 10L, null);
            RoadmapNode second = node(2L, 10L, null);
            RoadmapNode child = node(3L, 10L, 1L);
            prepareNodes(List.of(first, second, child));
            AtomicReference<List<RoadmapNode>> savedNodes = new AtomicReference<>();
            when(nodeRepository.saveAll(anyList())).thenAnswer(invocation -> {
                List<RoadmapNode> nodes = invocation.getArgument(0);
                savedNodes.set(nodes);
                return Flux.fromIterable(nodes);
            });

            StepVerifier
                    .create(reorderRoadmapNodesService
                            .execute(7L, "MEMBER", 10L, new ReorderNodesReqDto(null, List.of(2L, 1L))))
                    .verifyComplete();

            assertEquals(List.of(2L, 1L), savedNodes.get().stream().map(RoadmapNode::getId).toList());
            assertEquals(List.of(0, 1), savedNodes.get().stream().map(RoadmapNode::getPosition).toList());
            assertEquals(List.of(7L, 7L), savedNodes.get().stream().map(RoadmapNode::getLastModifiedBy).toList());
        }
    }

    private void prepareNodes(List<RoadmapNode> nodes) {
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(nodeRepository.findByRoadmapIdOrderByPositionAsc(10L)).thenReturn(Flux.fromIterable(nodes));
    }

    private static Roadmap roadmap(Long id) {
        return Roadmap.builder().id(id).scope(RoadmapScope.GLOBAL.name()).build();
    }

    private static RoadmapNode node(Long id, Long roadmapId, Long parentId) {
        return RoadmapNode.builder()
                .id(id)
                .roadmapId(roadmapId)
                .parentId(parentId)
                .position(0)
                .title("node" + id)
                .build();
    }
}
