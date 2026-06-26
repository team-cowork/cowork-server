package com.cowork.roadmap.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.presentation.data.request.ReorderNodesRequest;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class RoadmapNodeServiceTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapNodeReferenceRepository referenceRepository = mock(RoadmapNodeReferenceRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapNodeService nodeService = new RoadmapNodeService(nodeRepository,
            referenceRepository,
            roadmapRepository,
            accessGuard);

    @Test
    void reorderNodes_idsDoNotMatchSiblingSet_failsWithBadRequest() {
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(nodeRepository.findByRoadmapIdOrderByPositionAsc(10L))
                .thenReturn(Flux.fromIterable(List.of(node(1L, 10L, null), node(2L, 10L, null))));

        ReorderNodesRequest request = new ReorderNodesRequest(null, List.of(1L, 2L, 3L));

        StepVerifier.create(nodeService.reorderNodes(1L, "ADMIN", 10L, request))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    void deleteNode_removesTargetAndAllDescendants() {
        RoadmapNode root = node(1L, 10L, null);
        RoadmapNode child = node(2L, 10L, 1L);
        RoadmapNode grandChild = node(3L, 10L, 2L);
        RoadmapNode otherRoot = node(4L, 10L, null);

        when(nodeRepository.findById(1L)).thenReturn(Mono.just(root));
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(nodeRepository.findByRoadmapIdOrderByPositionAsc(10L))
                .thenReturn(Flux.fromIterable(List.of(root, child, grandChild, otherRoot)));
        when(nodeRepository.deleteAllById(any())).thenReturn(Mono.empty());

        StepVerifier.create(nodeService.deleteNode(1L, "ADMIN", 1L)).verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Long>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(nodeRepository).deleteAllById(captor.capture());
        Set<Long> deleted = new HashSet<>();
        captor.getValue().forEach(deleted::add);
        assertThat(deleted).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    private static Roadmap roadmap(Long id) {
        Roadmap roadmap = new Roadmap();
        roadmap.setId(id);
        roadmap.setScope(RoadmapScope.GLOBAL.name());
        return roadmap;
    }

    private static RoadmapNode node(Long id, Long roadmapId, Long parentId) {
        RoadmapNode node = new RoadmapNode();
        node.setId(id);
        node.setRoadmapId(roadmapId);
        node.setParentId(parentId);
        node.setPosition(0);
        node.setTitle("node" + id);
        return node;
    }
}
