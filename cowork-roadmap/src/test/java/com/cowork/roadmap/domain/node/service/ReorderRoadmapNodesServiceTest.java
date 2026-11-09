package com.cowork.roadmap.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.node.presentation.data.request.ReorderNodesReqDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.repository.projection.NodePositionProjection;
import com.cowork.roadmap.domain.node.service.impl.ReorderRoadmapNodesServiceImpl;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import team.themoment.sdk.exception.ExpectedException;

class ReorderRoadmapNodesServiceTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);
    private final ReorderRoadmapNodesServiceImpl service = new ReorderRoadmapNodesServiceImpl(nodeRepository,
            accessGuard,
            new RoadmapLookupSupport(roadmapRepository));

    @Test
    void missingExtraOrDuplicateSiblingIds_failBeforeUpdating() {
        prepareNodes(null, List.of(node(1L, 0), node(2L, 1)));

        for (List<Long> ids : List.of(List.of(1L), List.of(1L, 2L, 3L), List.of(1L, 1L))) {
            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> service.execute(7L, "MEMBER", 10L, new ReorderNodesReqDto(null, ids)));
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        verify(nodeRepository, never()).updatePosition(anyLong(), anyLong(), anyInt(), anyLong(), any());
    }

    @Test
    void childOrder_readsOnlyRequestedParentAfterLockingAndCheckingAccess() {
        Roadmap roadmap = prepareNodes(5L, List.of(node(1L, 0), node(2L, 1)));

        service.execute(7L, "MEMBER", 10L, new ReorderNodesReqDto(5L, List.of(2L, 1L)));

        var order = inOrder(roadmapRepository, accessGuard, nodeRepository);
        order.verify(roadmapRepository).findByIdForUpdate(10L);
        order.verify(accessGuard).requireMutable(roadmap, 7L, "MEMBER");
        order.verify(nodeRepository).findSiblingPositions(10L, 5L);
    }

    @Test
    void movedNodes_areParkedBeforeFinalPositionsAndAllSiblingsReceiveAuditFields() {
        prepareNodes(null, List.of(node(1L, 0), node(2L, 1), node(3L, 2)));
        List<Update> updates = captureUpdates();
        Instant before = Instant.now();

        service.execute(7L, "MEMBER", 10L, new ReorderNodesReqDto(null, List.of(3L, 2L, 1L)));

        assertThat(updates).extracting(Update::nodeId).containsExactly(3L, 1L, 3L, 2L, 1L);
        assertThat(updates).extracting(Update::position).containsExactly(-1, -3, 0, 1, 2);
        assertThat(updates).allSatisfy(update -> {
            assertThat(update.roadmapId()).isEqualTo(10L);
            assertThat(update.userId()).isEqualTo(7L);
            assertThat(update.updatedAt()).isBetween(before, Instant.now());
        });
        assertThat(updates).extracting(Update::updatedAt).containsOnly(updates.getFirst().updatedAt());
    }

    @Test
    void positionsWithDeletionGaps_areCompacted() {
        prepareNodes(null, List.of(node(1L, 2), node(2L, 7)));
        List<Update> updates = captureUpdates();

        service.execute(7L, "MEMBER", 10L, new ReorderNodesReqDto(null, List.of(1L, 2L)));

        assertThat(updates).extracting(Update::position).containsExactly(-1, -2, 0, 1);
    }

    private List<Update> captureUpdates() {
        List<Update> updates = new ArrayList<>();
        doAnswer(invocation -> {
            updates.add(new Update(invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4)));
            return null;
        }).when(nodeRepository).updatePosition(anyLong(), anyLong(), anyInt(), anyLong(), any());
        return updates;
    }

    private Roadmap prepareNodes(Long parentId, List<NodePositionProjection> nodes) {
        Roadmap roadmap = Roadmap.builder().id(10L).scope(RoadmapScope.GLOBAL.name()).build();
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap));
        when(nodeRepository.findSiblingPositions(10L, parentId)).thenReturn(nodes);
        return roadmap;
    }

    private NodePositionProjection node(Long id, int position) {
        NodePositionProjection node = mock(NodePositionProjection.class);
        when(node.getId()).thenReturn(id);
        when(node.getPosition()).thenReturn(position);
        return node;
    }

    private record Update(Long roadmapId, Long nodeId, Integer position, Long userId, Instant updatedAt) {
    }
}
