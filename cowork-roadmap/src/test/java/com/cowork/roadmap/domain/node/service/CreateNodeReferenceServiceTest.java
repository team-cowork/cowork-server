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
import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.presentation.data.request.NodeReferenceReqDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.impl.CreateNodeReferenceServiceImpl;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import team.themoment.sdk.exception.ExpectedException;

class CreateNodeReferenceServiceTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapNodeReferenceRepository referenceRepository = mock(RoadmapNodeReferenceRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final RoadmapNodeLookupSupport nodeLookupSupport = new RoadmapNodeLookupSupport(nodeRepository,
            referenceRepository);
    private final CreateNodeReferenceServiceImpl createNodeReferenceService = new CreateNodeReferenceServiceImpl(
            referenceRepository,
            accessGuard,
            roadmapLookupSupport,
            nodeLookupSupport);

    @Test
    void createReference_usesNextPositionAfterMax() {
        when(nodeRepository.findRoadmapIdById(5L)).thenReturn(Optional.of(10L));
        when(nodeRepository.findById(5L)).thenReturn(Optional.of(node(5L, 10L)));
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));

        when(referenceRepository.findNextPosition(5L)).thenReturn(3L);
        when(referenceRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            RoadmapNodeReference ref = invocation.getArgument(0);
            RoadmapNodeReference saved = ref.toBuilder().id(100L).build();
            // 실제 JPA saveAndFlush()는 @CreatedDate/@LastModifiedDate auditing이 채운 값을 돌려준다.
            saved.setCreatedAt(LocalDateTime.now());
            saved.setUpdatedAt(LocalDateTime.now());
            return saved;
        });

        NodeReferenceReqDto request = new NodeReferenceReqDto("자료", "https://example.com");

        var response = createNodeReferenceService.execute(7L, "ADMIN", 5L, request);
        assertThat(response.position()).isEqualTo(3);
        assertThat(response.title()).isEqualTo("자료");
    }

    @Test
    void createReference_nodeNotFound_failsWithNotFound() {
        when(nodeRepository.findRoadmapIdById(99L)).thenReturn(Optional.empty());

        NodeReferenceReqDto request = new NodeReferenceReqDto("자료", "https://example.com");

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> createNodeReferenceService.execute(7L, "ADMIN", 99L, request));
        assertTrue(error.getStatusCode() == HttpStatus.NOT_FOUND);
    }

    private static Roadmap roadmap(Long id) {
        return Roadmap.builder().id(id).scope(RoadmapScope.GLOBAL.name()).build();
    }

    private static RoadmapNode node(Long id, Long roadmapId) {
        return RoadmapNode.builder().id(id).roadmapId(roadmapId).build();
    }
}
