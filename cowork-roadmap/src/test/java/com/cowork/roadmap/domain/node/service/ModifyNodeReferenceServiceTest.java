package com.cowork.roadmap.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.presentation.data.request.NodeReferenceReqDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.impl.ModifyNodeReferenceServiceImpl;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import team.themoment.sdk.exception.ExpectedException;

class ModifyNodeReferenceServiceTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapNodeReferenceRepository referenceRepository = mock(RoadmapNodeReferenceRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final RoadmapNodeLookupSupport nodeLookupSupport = new RoadmapNodeLookupSupport(nodeRepository,
            referenceRepository);
    private final ModifyNodeReferenceServiceImpl modifyNodeReferenceService = new ModifyNodeReferenceServiceImpl(
            referenceRepository,
            accessGuard,
            roadmapLookupSupport,
            nodeLookupSupport);

    @Test
    void modifyReference_overwritesTitleAndUrl() {
        RoadmapNodeReference ref = reference(100L, 5L, "원제목", "https://old.com");
        when(referenceRepository.findById(100L)).thenReturn(Optional.of(ref));
        when(nodeRepository.findById(5L)).thenReturn(Optional.of(node(5L, 10L)));
        when(roadmapRepository.findById(10L)).thenReturn(Optional.of(roadmap(10L)));

        when(referenceRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        NodeReferenceReqDto request = new NodeReferenceReqDto("새제목", "https://new.com");

        var response = modifyNodeReferenceService.execute(7L, "ADMIN", 100L, request);
        assertThat(response.title()).isEqualTo("새제목");
        assertThat(response.url()).isEqualTo("https://new.com");
    }

    @Test
    void modifyReference_referenceNotFound_failsWithNotFound() {
        when(referenceRepository.findById(99L)).thenReturn(Optional.empty());

        NodeReferenceReqDto request = new NodeReferenceReqDto("새제목", "https://new.com");

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> modifyNodeReferenceService.execute(7L, "ADMIN", 99L, request));
        assertTrue(error.getStatusCode() == HttpStatus.NOT_FOUND);
    }

    private static Roadmap roadmap(Long id) {
        return Roadmap.builder().id(id).scope(RoadmapScope.GLOBAL.name()).build();
    }

    private static RoadmapNode node(Long id, Long roadmapId) {
        return RoadmapNode.builder().id(id).roadmapId(roadmapId).build();
    }

    private static RoadmapNodeReference reference(Long id, Long nodeId, String title, String url) {
        return RoadmapNodeReference.builder().id(id).nodeId(nodeId).title(title).url(url).position(0).build();
    }
}
