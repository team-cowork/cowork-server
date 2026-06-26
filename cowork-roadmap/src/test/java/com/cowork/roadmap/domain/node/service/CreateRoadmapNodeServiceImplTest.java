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
import com.cowork.roadmap.domain.node.presentation.data.request.CreateNodeReqDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.impl.CreateRoadmapNodeServiceImpl;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class CreateRoadmapNodeServiceImplTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final CreateRoadmapNodeServiceImpl createRoadmapNodeService = new CreateRoadmapNodeServiceImpl(
            nodeRepository,
            accessGuard,
            roadmapLookupSupport);

    @Test
    void createNode_asRoot_usesSiblingCountAsPosition() {
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(nodeRepository.countByRoadmapIdAndParentIdIsNull(10L)).thenReturn(Mono.just(2L));
        when(nodeRepository.save(any())).thenAnswer(invocation -> {
            RoadmapNode node = invocation.getArgument(0);
            node.setId(100L);
            return Mono.just(node);
        });

        CreateNodeReqDto request = new CreateNodeReqDto(null, "제목", "내용", null, null);

        StepVerifier.create(createRoadmapNodeService.execute(7L, "ADMIN", 10L, request)).assertNext(response -> {
            assertThat(response.position()).isEqualTo(2);
            assertThat(response.parentId()).isNull();
            assertThat(response.references()).isEmpty();
        }).verifyComplete();
    }

    @Test
    void createNode_withValidParent_usesChildCountAsPosition() {
        RoadmapNode parent = node(50L, 10L);
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(nodeRepository.findById(50L)).thenReturn(Mono.just(parent));
        when(nodeRepository.countByRoadmapIdAndParentId(10L, 50L)).thenReturn(Mono.just(1L));
        when(nodeRepository.save(any())).thenAnswer(invocation -> {
            RoadmapNode node = invocation.getArgument(0);
            node.setId(101L);
            return Mono.just(node);
        });

        CreateNodeReqDto request = new CreateNodeReqDto(50L, "제목", null, null, null);

        StepVerifier.create(createRoadmapNodeService.execute(7L, "ADMIN", 10L, request)).assertNext(response -> {
            assertThat(response.position()).isEqualTo(1);
            assertThat(response.parentId()).isEqualTo(50L);
        }).verifyComplete();
    }

    @Test
    void createNode_parentNotFound_failsWithNotFound() {
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(nodeRepository.findById(99L)).thenReturn(Mono.empty());
        // validateParent 에러 뒤 nextPosition 인자가 eager 평가되므로 count stub 필요(NPE 방지).
        when(nodeRepository.countByRoadmapIdAndParentId(10L, 99L)).thenReturn(Mono.just(0L));

        CreateNodeReqDto request = new CreateNodeReqDto(99L, "제목", null, null, null);

        StepVerifier.create(createRoadmapNodeService.execute(7L, "ADMIN", 10L, request))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void createNode_parentInDifferentRoadmap_failsWithBadRequest() {
        RoadmapNode parent = node(50L, 999L);
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap(10L)));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(nodeRepository.findById(50L)).thenReturn(Mono.just(parent));
        // validateParent 에러 뒤 nextPosition 인자가 eager 평가되므로 count stub 필요(NPE 방지).
        when(nodeRepository.countByRoadmapIdAndParentId(10L, 50L)).thenReturn(Mono.just(0L));

        CreateNodeReqDto request = new CreateNodeReqDto(50L, "제목", null, null, null);

        StepVerifier.create(createRoadmapNodeService.execute(7L, "ADMIN", 10L, request))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
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
}
