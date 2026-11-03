package com.cowork.roadmap.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.presentation.data.request.UpdateNodeReqDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.impl.ModifyRoadmapNodeServiceImpl;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import team.themoment.sdk.exception.ExpectedException;

class ModifyRoadmapNodeServiceTest {

    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapNodeReferenceRepository referenceRepository = mock(RoadmapNodeReferenceRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final RoadmapNodeLookupSupport nodeLookupSupport = new RoadmapNodeLookupSupport(nodeRepository,
            referenceRepository);
    private final ModifyRoadmapNodeServiceImpl modifyRoadmapNodeService = new ModifyRoadmapNodeServiceImpl(
            nodeRepository,
            accessGuard,
            roadmapLookupSupport,
            nodeLookupSupport);

    @Test
    void modifyNode_updatesOnlyNonNullFields() {
        RoadmapNode node = node(5L, 10L, "원제목", "원내용");
        when(nodeRepository.findRoadmapIdById(5L)).thenReturn(Optional.of(10L));
        when(nodeRepository.findById(5L)).thenReturn(Optional.of(node));
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));

        ArgumentCaptor<RoadmapNode> savedCaptor = ArgumentCaptor.forClass(RoadmapNode.class);
        when(nodeRepository.saveAndFlush(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        when(referenceRepository.findByNodeIdOrderByPositionAsc(5L)).thenReturn(List.of());

        UpdateNodeReqDto request = new UpdateNodeReqDto("새제목", null, null, null);

        var response = modifyRoadmapNodeService.execute(7L, "ADMIN", 5L, request);
        assertThat(response.title()).isEqualTo("새제목");
        assertThat(response.content()).isEqualTo("원내용");

        assertThat(savedCaptor.getValue().getLastModifiedBy()).isEqualTo(7L);
    }

    @Test
    void modifyNode_savesPositionReReadAfterRoadmapLock() {
        RoadmapNode stale = node(5L, 10L, "원제목", "원내용");
        RoadmapNode reordered = stale.toBuilder().position(3).build();
        when(nodeRepository.findRoadmapIdById(5L)).thenReturn(Optional.of(10L));
        when(nodeRepository.findById(5L)).thenReturn(Optional.of(reordered));
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));

        ArgumentCaptor<RoadmapNode> savedCaptor = ArgumentCaptor.forClass(RoadmapNode.class);
        when(nodeRepository.saveAndFlush(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        when(referenceRepository.findByNodeIdOrderByPositionAsc(5L)).thenReturn(List.of());

        assertThat(modifyRoadmapNodeService.execute(7L, "ADMIN", 5L, new UpdateNodeReqDto("새제목", null, null, null)))
                .isNotNull();

        assertThat(savedCaptor.getValue().getPosition()).isEqualTo(3);
        InOrder order = inOrder(nodeRepository, roadmapRepository);
        order.verify(nodeRepository).findRoadmapIdById(5L);
        order.verify(roadmapRepository).findByIdForUpdate(10L);
        order.verify(nodeRepository).findById(5L);
        order.verify(nodeRepository).saveAndFlush(savedCaptor.getValue());
    }

    @Test
    void modifyNode_nodeNotFound_failsWithNotFound() {
        when(nodeRepository.findRoadmapIdById(99L)).thenReturn(Optional.empty());

        UpdateNodeReqDto request = new UpdateNodeReqDto("새제목", null, null, null);

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> modifyRoadmapNodeService.execute(7L, "ADMIN", 99L, request));
        assertTrue(error.getStatusCode() == HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void modifyNode_blankTitle_failsWithBadRequestBeforeSave(String title) {
        when(nodeRepository.findRoadmapIdById(5L)).thenReturn(Optional.of(10L));
        when(nodeRepository.findById(5L)).thenReturn(Optional.of(node(5L, 10L, "원제목", "원내용")));
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap(10L)));

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> modifyRoadmapNodeService.execute(7L, "ADMIN", 5L, new UpdateNodeReqDto(title, null, null, null)));

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(nodeRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @MethodSource("validTitles")
    void modifyNode_validTitle_savesInputAsIs(String title) {
        stubSuccessfulModify(node(5L, 10L, "원제목", "원내용"));

        var response = modifyRoadmapNodeService.execute(7L, "ADMIN", 5L, new UpdateNodeReqDto(title, null, null, null));

        assertThat(response.title()).isEqualTo(title);
    }

    @Test
    void modifyNode_nullTitle_keepsTitleAndUpdatesOtherFields() {
        stubSuccessfulModify(node(5L, 10L, "원제목", "원내용"));

        var response = modifyRoadmapNodeService.execute(7L, "ADMIN", 5L, new UpdateNodeReqDto(null, "새내용", null, null));

        assertThat(response.title()).isEqualTo("원제목");
        assertThat(response.content()).isEqualTo("새내용");
    }

    private void stubSuccessfulModify(RoadmapNode node) {
        when(nodeRepository.findRoadmapIdById(node.getId())).thenReturn(Optional.of(node.getRoadmapId()));
        when(nodeRepository.findById(node.getId())).thenReturn(Optional.of(node));
        when(roadmapRepository.findByIdForUpdate(node.getRoadmapId()))
                .thenReturn(Optional.of(roadmap(node.getRoadmapId())));
        when(nodeRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(referenceRepository.findByNodeIdOrderByPositionAsc(node.getId())).thenReturn(List.of());
    }

    private static Stream<String> validTitles() {
        return Stream.of("가", "가".repeat(200), " Flutter 기초 ");
    }

    private static Roadmap roadmap(Long id) {
        return Roadmap.builder().id(id).scope(RoadmapScope.GLOBAL.name()).build();
    }

    private static RoadmapNode node(Long id, Long roadmapId, String title, String content) {
        return RoadmapNode.builder().id(id).roadmapId(roadmapId).title(title).content(content).position(0).build();
    }
}
