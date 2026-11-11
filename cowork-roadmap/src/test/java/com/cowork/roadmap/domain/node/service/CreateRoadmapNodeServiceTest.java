package com.cowork.roadmap.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

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

import team.themoment.sdk.exception.ExpectedException;

class CreateRoadmapNodeServiceTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final CreateRoadmapNodeServiceImpl createRoadmapNodeService = new CreateRoadmapNodeServiceImpl(
            nodeRepository,
            accessGuard,
            roadmapLookupSupport);

    @Test
    void createNode_asRoot_usesNextPositionAfterSiblingMax() {
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));

        when(nodeRepository.findNextRootPosition(10L)).thenReturn(2L);
        when(nodeRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            RoadmapNode node = invocation.getArgument(0);
            return simulateSaved(node, 100L);
        });

        CreateNodeReqDto request = new CreateNodeReqDto(null, "제목", "내용", null, null);

        var response = createRoadmapNodeService.execute(7L, "ADMIN", 10L, request);
        assertThat(response.position()).isEqualTo(2);
        assertThat(response.parentId()).isNull();
        assertThat(response.references()).isEmpty();
    }

    @Test
    void createNode_withValidParent_usesNextPositionAfterChildMax() {
        RoadmapNode parent = node(50L, 10L);
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));

        when(nodeRepository.findByIdForShare(50L)).thenReturn(Optional.of(parent));
        when(nodeRepository.findNextChildPosition(10L, 50L)).thenReturn(1L);
        when(nodeRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            RoadmapNode node = invocation.getArgument(0);
            return simulateSaved(node, 101L);
        });

        CreateNodeReqDto request = new CreateNodeReqDto(50L, "제목", null, null, null);

        var response = createRoadmapNodeService.execute(7L, "ADMIN", 10L, request);
        assertThat(response.position()).isEqualTo(1);
        assertThat(response.parentId()).isEqualTo(50L);
    }

    @Test
    void createNode_parentNotFound_failsWithNotFound() {
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));

        when(nodeRepository.findByIdForShare(99L)).thenReturn(Optional.empty());
        when(nodeRepository.findNextChildPosition(10L, 99L)).thenReturn(0L);

        CreateNodeReqDto request = new CreateNodeReqDto(99L, "제목", null, null, null);

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> createRoadmapNodeService.execute(7L, "ADMIN", 10L, request));
        assertTrue(error.getStatusCode() == HttpStatus.NOT_FOUND);
    }

    @Test
    void createNode_parentInDifferentRoadmap_failsWithBadRequest() {
        RoadmapNode parent = node(50L, 999L);
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));

        when(nodeRepository.findByIdForShare(50L)).thenReturn(Optional.of(parent));
        when(nodeRepository.findNextChildPosition(10L, 50L)).thenReturn(0L);

        CreateNodeReqDto request = new CreateNodeReqDto(50L, "제목", null, null, null);

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> createRoadmapNodeService.execute(7L, "ADMIN", 10L, request));
        assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void createNode_belowNodeLimit_saves() {
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));
        when(nodeRepository.countByRoadmapId(10L)).thenReturn((long) RoadmapNode.MAX_NODES_PER_ROADMAP - 1);
        when(nodeRepository.findNextRootPosition(10L)).thenReturn(0L);
        when(nodeRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> simulateSaved(invocation.getArgument(0), 102L));

        var response = createRoadmapNodeService
                .execute(7L, "ADMIN", 10L, new CreateNodeReqDto(null, "제목", null, null, null));

        assertThat(response.id()).isEqualTo(102L);
    }

    @Test
    void createNode_atNodeLimit_failsWithBadRequestAndDoesNotSave() {
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));
        when(nodeRepository.countByRoadmapId(10L)).thenReturn((long) RoadmapNode.MAX_NODES_PER_ROADMAP);

        CreateNodeReqDto request = new CreateNodeReqDto(null, "제목", null, null, null);

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> createRoadmapNodeService.execute(7L, "ADMIN", 10L, request));
        assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
        verify(nodeRepository, never()).saveAndFlush(any());
    }

    @Test
    void createNode_atMaxDepth_saves() {
        long parentId = stubChainOfDepth(RoadmapNode.MAX_TREE_DEPTH - 1);
        when(nodeRepository.findNextChildPosition(10L, parentId)).thenReturn(0L);
        when(nodeRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> simulateSaved(invocation.getArgument(0), 103L));

        var response = createRoadmapNodeService
                .execute(7L, "ADMIN", 10L, new CreateNodeReqDto(parentId, "제목", null, null, null));

        assertThat(response.parentId()).isEqualTo(parentId);
    }

    @Test
    void createNode_withBlankSourceUrl_savesNullSourceUrl() {
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));
        when(nodeRepository.findNextRootPosition(10L)).thenReturn(0L);
        when(nodeRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> simulateSaved(invocation.getArgument(0), 103L));

        var response = createRoadmapNodeService
                .execute(7L, "ADMIN", 10L, new CreateNodeReqDto(null, "제목", null, " ", null));

        assertThat(response.sourceUrl()).isNull();
    }

    @Test
    void createNode_beyondMaxDepth_failsWithBadRequestAndDoesNotSave() {
        long parentId = stubChainOfDepth(RoadmapNode.MAX_TREE_DEPTH);

        CreateNodeReqDto request = new CreateNodeReqDto(parentId, "제목", null, null, null);

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> createRoadmapNodeService.execute(7L, "ADMIN", 10L, request));
        assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
        verify(nodeRepository, never()).saveAndFlush(any());
    }

    @Test
    void createNode_contentWithBlockedSchemeLink_failsWithBadRequestAndDoesNotSave() {
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));

        CreateNodeReqDto request = new CreateNodeReqDto(null, "제목", "[x](javascript:alert(1))", null, null);

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> createRoadmapNodeService.execute(7L, "ADMIN", 10L, request));
        assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
        verify(nodeRepository, never()).saveAndFlush(any());
    }

    /** 로드맵 10에 1..depth 번 노드를 한 줄로 잇고, 가장 깊은 노드 ID를 반환한다. */
    private long stubChainOfDepth(int depth) {
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));
        for (long id = 2; id < depth; id++) {
            when(nodeRepository.findParentIdById(id)).thenReturn(Optional.of(id - 1));
        }
        RoadmapNode deepest = node((long) depth, 10L).toBuilder().parentId(depth == 1 ? null : depth - 1L).build();
        when(nodeRepository.findByIdForShare((long) depth)).thenReturn(Optional.of(deepest));
        return depth;
    }

    private static Roadmap roadmap(Long id) {
        return Roadmap.builder().id(id).scope(RoadmapScope.GLOBAL.name()).build();
    }

    private static RoadmapNode node(Long id, Long roadmapId) {
        return RoadmapNode.builder().id(id).roadmapId(roadmapId).build();
    }

    // toBuilder()는 상속 필드(createdBy/lastModifiedBy/createdAt/updatedAt)를 인식하지 못해
    // 누락시키므로,
    // 실제 JPA saveAndFlush() 후 auditing이 채운 값을 보존한 엔티티가 반환되는 동작을 흉내낸다.
    private static RoadmapNode simulateSaved(RoadmapNode node, Long id) {
        RoadmapNode saved = node.toBuilder().id(id).build();
        saved.setCreatedBy(node.getCreatedBy());
        saved.setLastModifiedBy(node.getLastModifiedBy());
        saved.setCreatedAt(Instant.now());
        saved.setUpdatedAt(Instant.now());
        return saved;
    }
}
