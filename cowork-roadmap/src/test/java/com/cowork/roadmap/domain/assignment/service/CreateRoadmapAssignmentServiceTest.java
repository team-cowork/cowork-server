package com.cowork.roadmap.domain.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.assignment.entity.AssignmentStatus;
import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.presentation.data.request.CreateAssignmentReqDto;
import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.impl.CreateRoadmapAssignmentServiceImpl;
import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import team.themoment.sdk.exception.ExpectedException;

@DisplayName("CreateRoadmapAssignmentServiceImpl 클래스의")
class CreateRoadmapAssignmentServiceTest {

    private static final Long REQUESTER_ID = 1L;
    private static final Long ROADMAP_ID = 1L;
    private static final Long OWNER_TEAM_ID = 5L;
    private static final Long OTHER_TEAM_ID = 6L;
    private static final Long PROJECT_ID = 9L;
    private static final Long NODE_ID = 99L;
    private static final Long ASSIGNEE_ID = 42L;

    private final RoadmapAssignmentRepository assignmentRepository = mock(RoadmapAssignmentRepository.class);
    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapNodeRepository nodeRepository = mock(RoadmapNodeRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport roadmapLookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final CreateRoadmapAssignmentServiceImpl createRoadmapAssignmentService = new CreateRoadmapAssignmentServiceImpl(
            assignmentRepository,
            nodeRepository,
            accessGuard,
            roadmapLookupSupport);

    @Nested
    @DisplayName("execute 메서드는")
    class Describe_execute {

        @Nested
        @DisplayName("할당 범위가 GLOBAL이면")
        class Context_with_global_assignment_scope {

            @Test
            @DisplayName("BAD_REQUEST 예외를 던진다")
            void it_fails_with_bad_request() {
                // given
                CreateAssignmentReqDto request = request(RoadmapScope.GLOBAL, null, null);

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> createRoadmapAssignmentService.execute(REQUESTER_ID, "ADMIN", request));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            }
        }

        @Nested
        @DisplayName("글로벌 로드맵에 할당하면")
        class Context_with_global_roadmap {

            @Test
            @DisplayName("요청자 역할, 할당 범위, 노드 지정과 관계없이 BAD_REQUEST 예외를 던지고 저장하지 않는다")
            void it_rejects_every_combination() {
                // given
                Roadmap roadmap = Roadmap.builder().id(ROADMAP_ID).scope(RoadmapScope.GLOBAL.name()).build();
                given(roadmapRepository.findByIdForUpdate(ROADMAP_ID)).willReturn(Optional.of(roadmap));
                given(nodeRepository.findById(NODE_ID)).willReturn(Optional.of(node(NODE_ID, ROADMAP_ID)));

                for (String role : new String[]{"ADMIN", "MEMBER"}) {
                    for (RoadmapScope scope : new RoadmapScope[]{RoadmapScope.TEAM, RoadmapScope.PROJECT}) {
                        for (Long nodeId : new Long[]{null, NODE_ID}) {
                            CreateAssignmentReqDto request = request(scope,
                                    scope == RoadmapScope.PROJECT ? PROJECT_ID : null,
                                    nodeId);

                            // when
                            ExpectedException error = assertThrows(ExpectedException.class,
                                    () -> createRoadmapAssignmentService.execute(REQUESTER_ID, role, request));

                            // then
                            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        }
                    }
                }
                then(assignmentRepository).should(never()).saveAndFlush(any());
            }
        }

        @Nested
        @DisplayName("할당 범위가 PROJECT이면")
        class Context_with_project_scope {

            @Test
            @DisplayName("projectId와 관계없이 BAD_REQUEST 예외를 던진다")
            void it_fails_with_bad_request() {
                // given
                CreateAssignmentReqDto request = request(RoadmapScope.PROJECT, PROJECT_ID, null);

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> createRoadmapAssignmentService.execute(REQUESTER_ID, "ADMIN", request));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            }
        }

        @Nested
        @DisplayName("노드가 존재하지 않으면")
        class Context_with_missing_node {

            @Test
            @DisplayName("NOT_FOUND 예외를 던진다")
            void it_fails_with_not_found() {
                // given
                givenTeamRoadmap();
                given(nodeRepository.findById(NODE_ID)).willReturn(Optional.empty());

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> createRoadmapAssignmentService
                                .execute(REQUESTER_ID, "MEMBER", request(RoadmapScope.TEAM, null, NODE_ID)));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            }
        }

        @Nested
        @DisplayName("노드가 다른 로드맵에 속하면")
        class Context_with_node_from_other_roadmap {

            @Test
            @DisplayName("BAD_REQUEST 예외를 던진다")
            void it_fails_with_bad_request() {
                // given
                givenTeamRoadmap();
                given(nodeRepository.findById(NODE_ID)).willReturn(Optional.of(node(NODE_ID, 2L)));

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> createRoadmapAssignmentService
                                .execute(REQUESTER_ID, "MEMBER", request(RoadmapScope.TEAM, null, NODE_ID)));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            }
        }

        @Nested
        @DisplayName("과제 팀이 로드맵 소유 팀과 다르면")
        class Context_with_team_other_than_roadmap_owner {

            @ParameterizedTest
            @ValueSource(strings = {"ADMIN", "MEMBER"})
            @DisplayName("BAD_REQUEST 예외를 던지고 저장하지 않는다")
            void it_fails_with_bad_request_before_save(String role) {
                // given
                givenTeamRoadmap();
                CreateAssignmentReqDto request = new CreateAssignmentReqDto(ROADMAP_ID,
                        null,
                        RoadmapScope.TEAM,
                        OTHER_TEAM_ID,
                        null,
                        ASSIGNEE_ID,
                        null);

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> createRoadmapAssignmentService.execute(REQUESTER_ID, role, request));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                then(accessGuard).should(never()).requireAssignableMember(anyLong(), anyLong());
                then(assignmentRepository).should(never()).saveAndFlush(any());
            }
        }

        @Nested
        @DisplayName("담당자가 팀의 활성 멤버가 아니면")
        class Context_with_non_member_assignee {

            @ParameterizedTest
            @ValueSource(strings = {"ADMIN", "MEMBER"})
            @DisplayName("요청자가 ADMIN이어도 BAD_REQUEST 예외를 던지고 저장하지 않는다")
            void it_fails_with_bad_request_before_save(String role) {
                // given
                givenTeamRoadmap();
                willThrow(new ExpectedException("과제 담당자는 팀의 활성 멤버여야 합니다.", HttpStatus.BAD_REQUEST)).given(accessGuard)
                        .requireAssignableMember(OWNER_TEAM_ID, ASSIGNEE_ID);

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> createRoadmapAssignmentService
                                .execute(REQUESTER_ID, role, request(RoadmapScope.TEAM, null, null)));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                then(assignmentRepository).should(never()).saveAndFlush(any());
            }
        }

        @Nested
        @DisplayName("팀 멤버 투영이 준비되지 않았으면")
        class Context_with_projection_not_ready {

            @Test
            @DisplayName("SERVICE_UNAVAILABLE 예외를 던지고 저장하지 않는다")
            void it_fails_with_service_unavailable_before_save() {
                // given
                givenTeamRoadmap();
                willThrow(new ExpectedException("팀 멤버 투영을 동기화하는 중입니다.", HttpStatus.SERVICE_UNAVAILABLE))
                        .given(accessGuard)
                        .requireAssignableMember(OWNER_TEAM_ID, ASSIGNEE_ID);

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> createRoadmapAssignmentService
                                .execute(REQUESTER_ID, "ADMIN", request(RoadmapScope.TEAM, null, null)));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                then(assignmentRepository).should(never()).saveAndFlush(any());
            }
        }

        @Nested
        @DisplayName("같은 로드맵, 노드, 담당자의 과제가 이미 있으면")
        class Context_with_duplicate_assignment {

            @Test
            @DisplayName("노드를 지정한 할당은 CONFLICT 예외를 던지고 저장하지 않는다")
            void it_fails_with_conflict_for_node_assignment() {
                // given
                givenTeamRoadmap();
                given(nodeRepository.findById(NODE_ID)).willReturn(Optional.of(node(NODE_ID, ROADMAP_ID)));
                given(assignmentRepository
                        .countByRoadmapIdAndNodeKeyAndAssigneeUserId(ROADMAP_ID, NODE_ID, ASSIGNEE_ID)).willReturn(1L);

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> createRoadmapAssignmentService
                                .execute(REQUESTER_ID, "MEMBER", request(RoadmapScope.TEAM, null, NODE_ID)));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                then(assignmentRepository).should(never()).saveAndFlush(any());
            }

            @Test
            @DisplayName("로드맵 전체 할당은 node_key 0으로 판정해 CONFLICT 예외를 던진다")
            void it_fails_with_conflict_for_whole_roadmap_assignment() {
                // given
                givenTeamRoadmap();
                given(assignmentRepository.countByRoadmapIdAndNodeKeyAndAssigneeUserId(ROADMAP_ID, 0L, ASSIGNEE_ID))
                        .willReturn(1L);

                // when
                ExpectedException error = assertThrows(ExpectedException.class,
                        () -> createRoadmapAssignmentService
                                .execute(REQUESTER_ID, "MEMBER", request(RoadmapScope.TEAM, null, null)));

                // then
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                then(assignmentRepository).should(never()).saveAndFlush(any());
            }
        }

        @Nested
        @DisplayName("권한과 담당자가 유효하고 중복이 없으면")
        class Context_with_valid_assignment {

            @Test
            @DisplayName("기존 권한 확인 뒤 담당자와 중복을 확인하고 초기 상태로 저장한다")
            void it_stores_initial_business_state() {
                // given
                givenTeamRoadmap();
                given(nodeRepository.findById(NODE_ID)).willReturn(Optional.of(node(NODE_ID, ROADMAP_ID)));
                given(assignmentRepository.saveAndFlush(any(RoadmapAssignment.class)))
                        .willAnswer(invocation -> invocation.getArgument(0));

                // when
                AssignmentResDto response = createRoadmapAssignmentService
                        .execute(REQUESTER_ID, "MEMBER", request(RoadmapScope.TEAM, PROJECT_ID, NODE_ID));

                // then
                assertThat(response.roadmapId()).isEqualTo(ROADMAP_ID);
                assertThat(response.nodeId()).isEqualTo(NODE_ID);
                assertThat(response.scope()).isEqualTo(RoadmapScope.TEAM.name());
                assertThat(response.teamId()).isEqualTo(OWNER_TEAM_ID);
                assertThat(response.projectId()).isNull();
                assertThat(response.assigneeUserId()).isEqualTo(ASSIGNEE_ID);
                assertThat(response.assignedBy()).isEqualTo(REQUESTER_ID);
                assertThat(response.status()).isEqualTo(AssignmentStatus.ASSIGNED.name());

                InOrder order = inOrder(roadmapRepository, nodeRepository, accessGuard, assignmentRepository);
                order.verify(roadmapRepository).findByIdForUpdate(ROADMAP_ID);
                order.verify(accessGuard).requireReadable(any(Roadmap.class), any(), any());
                order.verify(accessGuard).requireTeamManagerOrAdmin(REQUESTER_ID, "MEMBER", OWNER_TEAM_ID);
                order.verify(nodeRepository).findById(NODE_ID);
                order.verify(accessGuard).requireAssignableMember(OWNER_TEAM_ID, ASSIGNEE_ID);
                order.verify(assignmentRepository)
                        .countByRoadmapIdAndNodeKeyAndAssigneeUserId(ROADMAP_ID, NODE_ID, ASSIGNEE_ID);
                order.verify(assignmentRepository).saveAndFlush(any(RoadmapAssignment.class));
            }
        }
    }

    private void givenTeamRoadmap() {
        Roadmap roadmap = Roadmap.builder()
                .id(ROADMAP_ID)
                .scope(RoadmapScope.TEAM.name())
                .ownerTeamId(OWNER_TEAM_ID)
                .build();
        given(roadmapRepository.findByIdForUpdate(ROADMAP_ID)).willReturn(Optional.of(roadmap));
    }

    private static CreateAssignmentReqDto request(RoadmapScope scope, Long projectId, Long nodeId) {
        return new CreateAssignmentReqDto(ROADMAP_ID, nodeId, scope, OWNER_TEAM_ID, projectId, ASSIGNEE_ID, null);
    }

    private static RoadmapNode node(Long id, Long roadmapId) {
        return RoadmapNode.builder().id(id).roadmapId(roadmapId).build();
    }
}
