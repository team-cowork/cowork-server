package com.cowork.roadmap.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.presentation.data.request.UpdateNodeReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeResDto;
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

@DisplayName("ModifyRoadmapNodeServiceImpl 클래스의")
class ModifyRoadmapNodeServiceTest {

    private static final Long USER_ID = 7L;
    private static final String USER_ROLE = "ADMIN";
    private static final Long NODE_ID = 5L;
    private static final Long ROADMAP_ID = 10L;

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

    @Nested
    @DisplayName("execute 메서드는")
    class Describe_execute {

        @Nested
        @DisplayName("노드가 존재하지 않으면")
        class Context_with_missing_node {

            @Test
            @DisplayName("NOT_FOUND 예외를 던진다")
            void it_fails_with_not_found() {
                // given
                given(nodeRepository.findRoadmapIdById(99L)).willReturn(Optional.empty());
                UpdateNodeReqDto request = new UpdateNodeReqDto("새제목", null, null, null);

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> modifyRoadmapNodeService.execute(USER_ID, USER_ROLE, 99L, request));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                then(nodeRepository).should(never()).saveAndFlush(any());
            }
        }

        @Nested
        @DisplayName("제목만 요청하면")
        class Context_with_title_only {

            @Test
            @DisplayName("제목만 바꾸고 나머지 필드와 수정자를 기록한다")
            void it_updates_only_title() {
                // given
                givenModifiableNode(node());
                ArgumentCaptor<RoadmapNode> savedCaptor = givenSaveCaptured();

                // when
                NodeResDto response = modifyRoadmapNodeService
                        .execute(USER_ID, USER_ROLE, NODE_ID, new UpdateNodeReqDto("새제목", null, null, null));

                // then
                assertThat(response.title()).isEqualTo("새제목");
                assertThat(response.content()).isEqualTo("원내용");
                assertThat(savedCaptor.getValue().getLastModifiedBy()).isEqualTo(USER_ID);
            }
        }

        @Nested
        @DisplayName("로드맵 잠금 전에 노드 순서가 바뀌었으면")
        class Context_with_position_changed_before_lock {

            @Test
            @DisplayName("잠금 이후 다시 읽은 순서를 저장한다")
            void it_saves_position_reread_after_lock() {
                // given
                RoadmapNode reordered = node().toBuilder().position(3).build();
                givenModifiableNode(reordered);
                ArgumentCaptor<RoadmapNode> savedCaptor = givenSaveCaptured();

                // when
                modifyRoadmapNodeService
                        .execute(USER_ID, USER_ROLE, NODE_ID, new UpdateNodeReqDto("새제목", null, null, null));

                // then
                assertThat(savedCaptor.getValue().getPosition()).isEqualTo(3);
                InOrder order = inOrder(nodeRepository, roadmapRepository);
                order.verify(nodeRepository).findRoadmapIdById(NODE_ID);
                order.verify(roadmapRepository).findByIdForUpdate(ROADMAP_ID);
                order.verify(nodeRepository).findById(NODE_ID);
                order.verify(nodeRepository).saveAndFlush(savedCaptor.getValue());
            }
        }

        @Nested
        @DisplayName("제목이 빈 문자열이거나 공백만 있으면")
        class Context_with_blank_title {

            @ParameterizedTest
            @ValueSource(strings = {"", "   ", "\t\n"})
            @DisplayName("BAD_REQUEST 예외를 던지고 저장하지 않는다")
            void it_fails_with_bad_request_before_save(String title) {
                // given
                givenModifiableNode(node());

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> modifyRoadmapNodeService
                                .execute(USER_ID, USER_ROLE, NODE_ID, new UpdateNodeReqDto(title, null, null, null)));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                then(nodeRepository).should(never()).saveAndFlush(any());
            }
        }

        @Nested
        @DisplayName("유효한 제목이면")
        class Context_with_valid_title {

            static Stream<String> validTitles() {
                return Stream.of("가", "가".repeat(200), " Flutter 기초 ");
            }

            @ParameterizedTest
            @MethodSource("validTitles")
            @DisplayName("입력값을 그대로 저장한다")
            void it_saves_title_as_is(String title) {
                // given
                givenModifiableNode(node());
                givenSaveCaptured();

                // when
                NodeResDto response = modifyRoadmapNodeService
                        .execute(USER_ID, USER_ROLE, NODE_ID, new UpdateNodeReqDto(title, null, null, null));

                // then
                assertThat(response.title()).isEqualTo(title);
            }
        }

        @Nested
        @DisplayName("제목 없이 다른 필드만 요청하면")
        class Context_without_title {

            @Test
            @DisplayName("제목은 유지하고 요청한 필드만 바꾼다")
            void it_keeps_title_and_updates_other_fields() {
                // given
                givenModifiableNode(node());
                givenSaveCaptured();

                // when
                NodeResDto response = modifyRoadmapNodeService
                        .execute(USER_ID, USER_ROLE, NODE_ID, new UpdateNodeReqDto(null, "새내용", null, null));

                // then
                assertThat(response.title()).isEqualTo("원제목");
                assertThat(response.content()).isEqualTo("새내용");
            }
        }
    }

    private void givenModifiableNode(RoadmapNode node) {
        given(nodeRepository.findRoadmapIdById(node.getId())).willReturn(Optional.of(node.getRoadmapId()));
        given(nodeRepository.findById(node.getId())).willReturn(Optional.of(node));
        given(roadmapRepository.findByIdForUpdate(node.getRoadmapId()))
                .willReturn(Optional.of(roadmap(node.getRoadmapId())));
        given(referenceRepository.findByNodeIdOrderByPositionAsc(node.getId())).willReturn(List.of());
    }

    private ArgumentCaptor<RoadmapNode> givenSaveCaptured() {
        ArgumentCaptor<RoadmapNode> savedCaptor = ArgumentCaptor.forClass(RoadmapNode.class);
        given(nodeRepository.saveAndFlush(savedCaptor.capture())).willAnswer(invocation -> invocation.getArgument(0));
        return savedCaptor;
    }

    private static Roadmap roadmap(Long id) {
        return Roadmap.builder().id(id).scope(RoadmapScope.GLOBAL.name()).build();
    }

    private static RoadmapNode node() {
        return RoadmapNode.builder().id(NODE_ID).roadmapId(ROADMAP_ID).title("원제목").content("원내용").position(0).build();
    }
}
