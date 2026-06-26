package com.cowork.roadmap.domain.roadmap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.CreateRoadmapRequest;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class RoadmapServiceTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapNodeReferenceRepository referenceRepository = mock(RoadmapNodeReferenceRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapService roadmapService = new RoadmapService(roadmapRepository,
            nodeRepository,
            referenceRepository,
            accessGuard);

    @Test
    void getRoadmapTree_assemblesNodesIntoNestedTreeWithReferences() {
        Roadmap roadmap = roadmap(10L, RoadmapScope.GLOBAL);
        RoadmapNode root1 = node(1L, 10L, null, 0, "root1");
        RoadmapNode child = node(2L, 10L, 1L, 0, "child");
        RoadmapNode root2 = node(3L, 10L, null, 1, "root2");
        RoadmapNodeReference ref = reference(100L, 2L, "관련자료");

        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap));
        when(accessGuard.requireReadable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(nodeRepository.findByRoadmapIdOrderByPositionAsc(10L))
                .thenReturn(Flux.fromIterable(List.of(root1, child, root2)));
        when(referenceRepository.findByNodeIdInOrderByNodeIdAscPositionAsc(any()))
                .thenReturn(Flux.fromIterable(List.of(ref)));

        StepVerifier.create(roadmapService.getRoadmapTree(1L, "ADMIN", 10L)).assertNext(tree -> {
            assertThat(tree.nodes()).hasSize(2);
            assertThat(tree.nodes().get(0).id()).isEqualTo(1L);
            assertThat(tree.nodes().get(0).children()).hasSize(1);
            assertThat(tree.nodes().get(0).children().get(0).id()).isEqualTo(2L);
            assertThat(tree.nodes().get(0).children().get(0).references()).hasSize(1);
            assertThat(tree.nodes().get(0).children().get(0).references().get(0).title()).isEqualTo("관련자료");
            assertThat(tree.nodes().get(1).id()).isEqualTo(3L);
            assertThat(tree.nodes().get(1).children()).isEmpty();
        }).verifyComplete();
    }

    @Test
    void createRoadmap_teamScopeWithoutOwnerTeamId_failsWithBadRequest() {
        CreateRoadmapRequest request = new CreateRoadmapRequest("title",
                null,
                "Flutter",
                RoadmapScope.TEAM,
                null,
                null);

        StepVerifier.create(roadmapService.createRoadmap(1L, "MEMBER", request))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    private static Roadmap roadmap(Long id, RoadmapScope scope) {
        Roadmap roadmap = new Roadmap();
        roadmap.setId(id);
        roadmap.setTitle("roadmap");
        roadmap.setCategory("Flutter");
        roadmap.setScope(scope.name());
        return roadmap;
    }

    private static RoadmapNode node(Long id, Long roadmapId, Long parentId, int position, String title) {
        RoadmapNode node = new RoadmapNode();
        node.setId(id);
        node.setRoadmapId(roadmapId);
        node.setParentId(parentId);
        node.setPosition(position);
        node.setTitle(title);
        return node;
    }

    private static RoadmapNodeReference reference(Long id, Long nodeId, String title) {
        RoadmapNodeReference ref = new RoadmapNodeReference();
        ref.setId(id);
        ref.setNodeId(nodeId);
        ref.setTitle(title);
        ref.setUrl("https://example.com");
        ref.setPosition(0);
        return ref;
    }
}
