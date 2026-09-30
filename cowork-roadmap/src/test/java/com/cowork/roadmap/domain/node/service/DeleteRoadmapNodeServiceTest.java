package com.cowork.roadmap.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.impl.DeleteRoadmapNodeServiceImpl;
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

class DeleteRoadmapNodeServiceTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapAssignmentRepository assignmentRepository = mock(RoadmapAssignmentRepository.class);
    private final RoadmapNodeReferenceRepository referenceRepository = mock(RoadmapNodeReferenceRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final RoadmapNodeLookupSupport nodeLookupSupport = new RoadmapNodeLookupSupport(nodeRepository,
            referenceRepository);
    private final DeleteRoadmapNodeServiceImpl deleteRoadmapNodeService = new DeleteRoadmapNodeServiceImpl(
            nodeRepository,
            assignmentRepository,
            accessGuard,
            roadmapLookupSupport,
            nodeLookupSupport);

    @Test
    void deleteNode_withoutAssignments_removesTargetAndAllDescendants() {
        prepareTree();
        when(assignmentRepository.countByRoadmapIdAndNodeIdInForShare(anyLong(), any())).thenReturn(Mono.just(0L));
        when(nodeRepository.deleteAllById(any())).thenReturn(Mono.empty());

        StepVerifier.create(deleteRoadmapNodeService.execute(1L, "ADMIN", 1L)).verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Long>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(nodeRepository).deleteAllById(captor.capture());
        assertThat(toSet(captor.getValue())).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void deleteNode_computesSubtreeFromLockedReadAndCountsAssignmentsOnlyWithinSubtree() {
        prepareTree();
        RoadmapNode concurrentGrandChild = node(5L, 10L, 3L);
        when(nodeRepository.findAllByRoadmapIdForUpdate(10L)).thenReturn(Flux.fromIterable(List.of(node(1L, 10L, null),
                node(2L, 10L, 1L),
                node(3L, 10L, 2L),
                node(4L, 10L, null),
                concurrentGrandChild)));
        when(assignmentRepository.countByRoadmapIdAndNodeIdInForShare(anyLong(), any())).thenReturn(Mono.just(0L));
        when(nodeRepository.deleteAllById(any())).thenReturn(Mono.empty());

        StepVerifier.create(deleteRoadmapNodeService.execute(1L, "ADMIN", 1L)).verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> countCaptor = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Long>> deleteCaptor = ArgumentCaptor.forClass(Iterable.class);
        InOrder inOrder = inOrder(nodeRepository, assignmentRepository);
        inOrder.verify(nodeRepository).findAllByRoadmapIdForUpdate(10L);
        inOrder.verify(assignmentRepository).countByRoadmapIdAndNodeIdInForShare(eq(10L), countCaptor.capture());
        inOrder.verify(nodeRepository).deleteAllById(deleteCaptor.capture());
        assertThat(countCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L, 5L);
        assertThat(toSet(deleteCaptor.getValue())).containsExactlyInAnyOrder(1L, 2L, 3L, 5L);
        verify(nodeRepository, never()).findByRoadmapIdOrderByPositionAsc(anyLong());
    }

    @Test
    void deleteNode_withAssignmentInSubtree_failsWithConflictAndKeepsNodes() {
        prepareTree();
        when(assignmentRepository.countByRoadmapIdAndNodeIdInForShare(anyLong(), any())).thenReturn(Mono.just(2L));

        StepVerifier.create(deleteRoadmapNodeService.execute(1L, "ADMIN", 1L))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.CONFLICT && expected.getMessage().contains("2건"))
                .verify();

        verify(nodeRepository, never()).deleteAllById(any());
    }

    private void prepareTree() {
        RoadmapNode root = node(1L, 10L, null);
        RoadmapNode child = node(2L, 10L, 1L);
        RoadmapNode grandChild = node(3L, 10L, 2L);
        RoadmapNode otherRoot = node(4L, 10L, null);

        when(nodeRepository.findById(1L)).thenReturn(Mono.just(root));
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(nodeRepository.findAllByRoadmapIdForUpdate(10L))
                .thenReturn(Flux.fromIterable(List.of(root, child, grandChild, otherRoot)));
    }

    private static Set<Long> toSet(Iterable<Long> ids) {
        Set<Long> result = new HashSet<>();
        ids.forEach(result::add);
        return result;
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
