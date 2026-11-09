package com.cowork.roadmap.domain.roadmap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.UpdateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.ModifyRoadmapServiceImpl;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import team.themoment.sdk.exception.ExpectedException;

@DisplayName("ModifyRoadmapServiceImpl 클래스의")
class ModifyRoadmapServiceTest {

    private static final Long USER_ID = 7L;
    private static final String USER_ROLE = "ADMIN";
    private static final Long ROADMAP_ID = 10L;

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport lookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final ModifyRoadmapServiceImpl modifyRoadmapService = new ModifyRoadmapServiceImpl(roadmapRepository,
            accessGuard,
            lookupSupport);

    @Nested
    @DisplayName("execute 메서드는")
    class Describe_execute {

        @Nested
        @DisplayName("로드맵이 존재하지 않으면")
        class Context_with_missing_roadmap {

            @Test
            @DisplayName("NOT_FOUND 예외를 던진다")
            void it_fails_with_not_found() {
                // given
                given(roadmapRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());
                UpdateRoadmapReqDto request = new UpdateRoadmapReqDto("새제목", null, null);

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> modifyRoadmapService.execute(USER_ID, USER_ROLE, 99L, request));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                then(roadmapRepository).should(never()).saveAndFlush(any());
            }
        }

        @Nested
        @DisplayName("제목만 요청하면")
        class Context_with_title_only {

            @Test
            @DisplayName("제목만 바꾸고 나머지 필드와 수정자를 기록한다")
            void it_updates_only_title() {
                // given
                givenLockedRoadmap(roadmap());
                ArgumentCaptor<Roadmap> savedCaptor = givenSaveCaptured();

                // when
                RoadmapResDto response = modifyRoadmapService
                        .execute(USER_ID, USER_ROLE, ROADMAP_ID, new UpdateRoadmapReqDto("새제목", null, null));

                // then
                assertThat(response.title()).isEqualTo("새제목");
                assertThat(response.description()).isEqualTo("원설명");
                assertThat(response.category()).isEqualTo("원카테고리");
                assertThat(savedCaptor.getValue().getLastModifiedBy()).isEqualTo(USER_ID);
                then(roadmapRepository).should(never()).findById(any());
            }
        }

        @Nested
        @DisplayName("변경할 필드가 없으면")
        class Context_without_field_changes {

            @Test
            @DisplayName("수정 시각만 갱신하고 생성 감사 정보를 유지한다")
            void it_refreshes_modified_time_and_keeps_creation_audit() {
                // given
                Roadmap roadmap = roadmap();
                Instant createdAt = Instant.parse("2025-01-01T00:00:00Z");
                Instant updatedAt = createdAt.plus(1, ChronoUnit.DAYS);
                roadmap.setCreatedAt(createdAt);
                roadmap.setUpdatedAt(updatedAt);
                roadmap.setCreatedBy(3L);
                roadmap.setLastModifiedBy(USER_ID);
                givenLockedRoadmap(roadmap);
                ArgumentCaptor<Roadmap> savedCaptor = givenSaveCaptured();

                // when
                RoadmapResDto response = modifyRoadmapService
                        .execute(USER_ID, USER_ROLE, ROADMAP_ID, new UpdateRoadmapReqDto(null, null, null));

                // then
                Roadmap saved = savedCaptor.getValue();
                assertThat(saved.getTitle()).isEqualTo(roadmap.getTitle());
                assertThat(saved.getDescription()).isEqualTo(roadmap.getDescription());
                assertThat(saved.getCategory()).isEqualTo(roadmap.getCategory());
                assertThat(saved.getCreatedAt()).isEqualTo(createdAt);
                assertThat(saved.getCreatedBy()).isEqualTo(3L);
                assertThat(saved.getLastModifiedBy()).isEqualTo(USER_ID);
                assertThat(saved.getUpdatedAt()).isAfter(updatedAt);
                assertThat(response.createdAt()).isEqualTo(createdAt);
                assertThat(response.updatedAt()).isEqualTo(saved.getUpdatedAt());
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
                givenLockedRoadmap(roadmap());

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> modifyRoadmapService
                                .execute(USER_ID, USER_ROLE, ROADMAP_ID, new UpdateRoadmapReqDto(title, null, null)));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                then(roadmapRepository).should(never()).saveAndFlush(any());
            }
        }

        @Nested
        @DisplayName("종류가 빈 문자열이거나 공백만 있으면")
        class Context_with_blank_category {

            @ParameterizedTest
            @ValueSource(strings = {"", "   ", "\t\n"})
            @DisplayName("BAD_REQUEST 예외를 던지고 저장하지 않는다")
            void it_fails_with_bad_request_before_save(String category) {
                // given
                givenLockedRoadmap(roadmap());

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> modifyRoadmapService.execute(USER_ID,
                                USER_ROLE,
                                ROADMAP_ID,
                                new UpdateRoadmapReqDto("새제목", null, category)));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                then(roadmapRepository).should(never()).saveAndFlush(any());
            }
        }

        @Nested
        @DisplayName("유효한 제목이면")
        class Context_with_valid_title {

            static Stream<String> validTitles() {
                return Stream.of("가", "가".repeat(150), " Flutter 기초 ");
            }

            @ParameterizedTest
            @MethodSource("validTitles")
            @DisplayName("입력값을 그대로 저장한다")
            void it_saves_title_as_is(String title) {
                // given
                givenLockedRoadmap(roadmap());
                givenSaveCaptured();

                // when
                RoadmapResDto response = modifyRoadmapService
                        .execute(USER_ID, USER_ROLE, ROADMAP_ID, new UpdateRoadmapReqDto(title, null, null));

                // then
                assertThat(response.title()).isEqualTo(title);
            }
        }

        @Nested
        @DisplayName("제목 없이 유효한 종류만 요청하면")
        class Context_with_valid_category_only {

            static Stream<String> validCategories() {
                return Stream.of("가", "가".repeat(50), " Flutter ");
            }

            @ParameterizedTest
            @MethodSource("validCategories")
            @DisplayName("제목은 유지하고 종류를 입력값 그대로 저장한다")
            void it_keeps_title_and_saves_category_as_is(String category) {
                // given
                givenLockedRoadmap(roadmap());
                givenSaveCaptured();

                // when
                RoadmapResDto response = modifyRoadmapService
                        .execute(USER_ID, USER_ROLE, ROADMAP_ID, new UpdateRoadmapReqDto(null, null, category));

                // then
                assertThat(response.title()).isEqualTo("원제목");
                assertThat(response.category()).isEqualTo(category);
            }
        }
    }

    private void givenLockedRoadmap(Roadmap roadmap) {
        given(roadmapRepository.findByIdForUpdate(roadmap.getId())).willReturn(Optional.of(roadmap));
    }

    private ArgumentCaptor<Roadmap> givenSaveCaptured() {
        ArgumentCaptor<Roadmap> savedCaptor = ArgumentCaptor.forClass(Roadmap.class);
        given(roadmapRepository.saveAndFlush(savedCaptor.capture()))
                .willAnswer(invocation -> invocation.getArgument(0));
        return savedCaptor;
    }

    private static Roadmap roadmap() {
        return Roadmap.builder()
                .id(ROADMAP_ID)
                .scope(RoadmapScope.GLOBAL.name())
                .title("원제목")
                .description("원설명")
                .category("원카테고리")
                .build();
    }
}
