package com.cowork.roadmap.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
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
        saved.setCreatedAt(LocalDateTime.now());
        saved.setUpdatedAt(LocalDateTime.now());
        return saved;
    }
}
