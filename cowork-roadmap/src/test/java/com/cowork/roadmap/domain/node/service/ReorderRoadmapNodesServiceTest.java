package com.cowork.roadmap.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> reorderRoadmapNodesService.execute(1L, "ADMIN", 10L, request));
            assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
        }

        @Test
        void duplicateIds_failWithBadRequest() {
            prepareNodes(List.of(node(1L, 10L, null), node(2L, 10L, null)));
            ReorderNodesReqDto request = new ReorderNodesReqDto(null, List.of(1L, 1L));

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> reorderRoadmapNodesService.execute(1L, "ADMIN", 10L, request));
            assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
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
                return nodes;
            });

            reorderRoadmapNodesService.execute(7L, "MEMBER", 10L, new ReorderNodesReqDto(null, List.of(2L, 1L)));

            assertEquals(List.of(2L, 1L), savedNodes.get().stream().map(RoadmapNode::getId).toList());
            assertEquals(List.of(0, 1), savedNodes.get().stream().map(RoadmapNode::getPosition).toList());
            assertEquals(List.of(7L, 7L), savedNodes.get().stream().map(RoadmapNode::getLastModifiedBy).toList());
        }

        @Test
        void movedNodes_areParkedAtNegativePositionsBeforeFinalSave() {
            RoadmapNode first = node(1L, 10L, null, 0);
            RoadmapNode fixed = node(2L, 10L, null, 1);
            LocalDateTime previousModifiedAt = LocalDateTime.of(2025, 1, 2, 0, 0);
            fixed.setCreatedAt(previousModifiedAt.minusDays(1));
            fixed.setUpdatedAt(previousModifiedAt);
            fixed.setLastModifiedBy(7L);
            RoadmapNode last = node(3L, 10L, null, 2);
            prepareNodes(List.of(first, fixed, last));
            List<List<RoadmapNode>> saveCalls = new ArrayList<>();
            when(nodeRepository.saveAllAndFlush(anyList())).thenAnswer(invocation -> {
                List<RoadmapNode> nodes = invocation.getArgument(0);
                saveCalls.add(nodes);
                return nodes;
            });
            when(nodeRepository.saveAll(anyList())).thenAnswer(invocation -> {
                List<RoadmapNode> nodes = invocation.getArgument(0);
                saveCalls.add(nodes);
                return nodes;
            });

            reorderRoadmapNodesService.execute(7L, "MEMBER", 10L, new ReorderNodesReqDto(null, List.of(3L, 2L, 1L)));

            InOrder order = inOrder(nodeRepository);
            order.verify(nodeRepository).saveAllAndFlush(saveCalls.get(0));
            order.verify(nodeRepository).saveAll(saveCalls.get(1));
            assertEquals(2, saveCalls.size());
            assertEquals(List.of(3L, 1L), saveCalls.get(0).stream().map(RoadmapNode::getId).toList());
            assertEquals(List.of(-1, -3), saveCalls.get(0).stream().map(RoadmapNode::getPosition).toList());
            assertEquals(List.of(3L, 2L, 1L), saveCalls.get(1).stream().map(RoadmapNode::getId).toList());
            assertEquals(List.of(0, 1, 2), saveCalls.get(1).stream().map(RoadmapNode::getPosition).toList());
            RoadmapNode unchangedPosition = saveCalls.get(1).get(1);
            assertThat(unchangedPosition.getCreatedAt()).isEqualTo(fixed.getCreatedAt());
            assertThat(unchangedPosition.getLastModifiedBy()).isEqualTo(7L);
            assertThat(unchangedPosition.getUpdatedAt()).isAfter(previousModifiedAt);
        }
    }

    private void prepareNodes(List<RoadmapNode> nodes) {
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));

        when(nodeRepository.findByRoadmapIdOrderByPositionAsc(10L)).thenReturn(nodes);
    }

    private static Roadmap roadmap(Long id) {
        return Roadmap.builder().id(id).scope(RoadmapScope.GLOBAL.name()).build();
    }

    private static RoadmapNode node(Long id, Long roadmapId, Long parentId) {
        return node(id, roadmapId, parentId, 0);
    }

    private static RoadmapNode node(Long id, Long roadmapId, Long parentId, int position) {
        return RoadmapNode.builder()
                .id(id)
                .roadmapId(roadmapId)
                .parentId(parentId)
                .position(position)
                .title("node" + id)
                .build();
    }
}
