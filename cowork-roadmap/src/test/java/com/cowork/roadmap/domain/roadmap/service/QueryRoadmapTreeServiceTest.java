package com.cowork.roadmap.domain.roadmap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.repository.projection.NodeTreeProjection;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.QueryRoadmapTreeServiceImpl;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import team.themoment.sdk.exception.ExpectedException;

class QueryRoadmapTreeServiceTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapNodeReferenceRepository referenceRepository = mock(RoadmapNodeReferenceRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport lookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final QueryRoadmapTreeServiceImpl queryRoadmapTreeService = new QueryRoadmapTreeServiceImpl(nodeRepository,
            referenceRepository,
            accessGuard,
            lookupSupport);

    @Test
    void getRoadmapTree_assemblesNodesIntoNestedTreeWithReferences() {
        Roadmap roadmap = roadmap(10L, RoadmapScope.GLOBAL);
        NodeTreeProjection root1 = node(1L, null, 0, "root1");
        NodeTreeProjection child = node(2L, 1L, 0, "child");
        NodeTreeProjection root2 = node(3L, null, 1, "root2");
        RoadmapNodeReference ref = reference(100L, 2L, "관련자료");

        when(roadmapRepository.findById(10L)).thenReturn(Optional.of(roadmap));

        when(nodeRepository.findTreeByRoadmapId(10L)).thenReturn(List.of(root1, child, root2));
        when(referenceRepository.findByNodeIdInOrderByNodeIdAscPositionAsc(any())).thenReturn(List.of(ref));

        var tree = queryRoadmapTreeService.execute(1L, "ADMIN", 10L);
        assertThat(tree.nodes()).hasSize(2);
        assertThat(tree.nodes().getFirst().title()).isEqualTo("root1");
        assertThat(tree.nodes().getFirst().sourceUrl()).isEqualTo("https://example.com/source");
        assertThat(tree.nodes().getFirst().sourceTitle()).isEqualTo("원본 문서");
        assertThat(tree.nodes().get(0).id()).isEqualTo(1L);
        assertThat(tree.nodes().get(0).children()).hasSize(1);
        assertThat(tree.nodes().get(0).children().get(0).id()).isEqualTo(2L);
        assertThat(tree.nodes().get(0).children().get(0).references()).hasSize(1);
        assertThat(tree.nodes().get(0).children().get(0).references().get(0).title()).isEqualTo("관련자료");
        assertThat(tree.nodes().get(1).id()).isEqualTo(3L);
        assertThat(tree.nodes().get(1).children()).isEmpty();
    }

    @Test
    void getRoadmapTree_returnsTreeAtMaxDepth() {
        stubChainOfDepth(RoadmapNode.MAX_TREE_DEPTH);

        var tree = queryRoadmapTreeService.execute(1L, "ADMIN", 10L);

        int depth = 0;
        for (var level = tree.nodes(); !level.isEmpty(); level = level.getFirst().children()) {
            depth++;
        }
        assertThat(depth).isEqualTo(RoadmapNode.MAX_TREE_DEPTH);
    }

    @Test
    void getRoadmapTree_rejectsTreeDeeperThanMaxDepth() {
        stubChainOfDepth(RoadmapNode.MAX_TREE_DEPTH + 1);

        assertThatThrownBy(() -> queryRoadmapTreeService.execute(1L, "ADMIN", 10L)).isInstanceOfSatisfying(
                ExpectedException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    /** 루트부터 한 줄로 이어진 depth단계 트리를 로드맵 10에 심는다. */
    private void stubChainOfDepth(int depth) {
        List<NodeTreeProjection> chain = new ArrayList<>();
        for (long id = 1; id <= depth; id++) {
            chain.add(node(id, id == 1 ? null : id - 1, 0, "node"));
        }
        when(roadmapRepository.findById(10L)).thenReturn(Optional.of(roadmap(10L, RoadmapScope.GLOBAL)));
        when(nodeRepository.findTreeByRoadmapId(10L)).thenReturn(chain);
        when(referenceRepository.findByNodeIdInOrderByNodeIdAscPositionAsc(any())).thenReturn(List.of());
    }

    private static Roadmap roadmap(Long id, RoadmapScope scope) {
        return Roadmap.builder().id(id).title("roadmap").category("Flutter").scope(scope.name()).build();
    }

    private static NodeTreeProjection node(Long id, Long parentId, int position, String title) {
        NodeTreeProjection node = mock(NodeTreeProjection.class);
        when(node.getId()).thenReturn(id);
        when(node.getParentId()).thenReturn(parentId);
        when(node.getPosition()).thenReturn(position);
        when(node.getTitle()).thenReturn(title);
        when(node.getSourceUrl()).thenReturn("https://example.com/source");
        when(node.getSourceTitle()).thenReturn("원본 문서");
        return node;
    }

    private static RoadmapNodeReference reference(Long id, Long nodeId, String title) {
        return RoadmapNodeReference.builder()
                .id(id)
                .nodeId(nodeId)
                .title(title)
                .url("https://example.com")
                .position(0)
                .build();
    }
}
