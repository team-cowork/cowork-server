package com.cowork.roadmap.domain.roadmap.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.CreateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.UpdateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapTreeResDto;
import com.cowork.roadmap.domain.roadmap.service.CreateRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.DeleteRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.ListRoadmapsService;
import com.cowork.roadmap.domain.roadmap.service.ModifyRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.QueryRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.QueryRoadmapTreeService;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Gateway가 전달하는 X-User-Id/X-User-Role 헤더가 컨트롤러 메서드 파라미터로 정확히 바인딩되어 서비스로 그대로
 * 전달되는지 검증하는 순수 단위 테스트. 스프링 컨텍스트를 기동하지 않고 컨트롤러를 직접 new 하여 헤더 인자를 호출부에서 흉내 낸다.
 */
class RoadmapControllerTest {

    private final CreateRoadmapService createRoadmapService = mock(CreateRoadmapService.class);
    private final ListRoadmapsService listRoadmapsService = mock(ListRoadmapsService.class);
    private final QueryRoadmapService queryRoadmapService = mock(QueryRoadmapService.class);
    private final QueryRoadmapTreeService queryRoadmapTreeService = mock(QueryRoadmapTreeService.class);
    private final ModifyRoadmapService modifyRoadmapService = mock(ModifyRoadmapService.class);
    private final DeleteRoadmapService deleteRoadmapService = mock(DeleteRoadmapService.class);

    private final RoadmapController controller = new RoadmapController(createRoadmapService,
            listRoadmapsService,
            queryRoadmapService,
            queryRoadmapTreeService,
            modifyRoadmapService,
            deleteRoadmapService);

    private static RoadmapResDto sampleRoadmap() {
        return new RoadmapResDto(1L, "title", "desc", "Flutter", "GLOBAL", null, null, 42L, null, null);
    }

    @Test
    void createRoadmap_forwardsUserIdAndRoleHeadersToService() {
        // Arrange
        Long userId = 42L;
        String userRole = "ADMIN";
        CreateRoadmapReqDto request = new CreateRoadmapReqDto("title",
                null,
                "Flutter",
                RoadmapScope.GLOBAL,
                null,
                null);
        when(createRoadmapService.execute(eq(userId), eq(userRole), any())).thenReturn(Mono.just(sampleRoadmap()));

        // Act
        Mono<ResponseEntity<RoadmapResDto>> result = controller.createRoadmap(userId, userRole, request);

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED))
                .verifyComplete();
        verify(createRoadmapService).execute(userId, userRole, request);
    }

    @Test
    void getRoadmap_forwardsUserIdAndRoleHeadersToService() {
        // Arrange
        Long userId = 7L;
        String userRole = "MEMBER";
        Long roadmapId = 99L;
        when(queryRoadmapService.execute(eq(userId), eq(userRole), eq(roadmapId)))
                .thenReturn(Mono.just(sampleRoadmap()));

        // Act
        Mono<RoadmapResDto> result = controller.getRoadmap(userId, userRole, roadmapId);

        // Assert
        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        verify(queryRoadmapService).execute(userId, userRole, roadmapId);
    }

    @Test
    void getRoadmapTree_forwardsUserIdAndRoleHeadersToService() {
        // Arrange
        Long userId = 3L;
        String userRole = "MEMBER";
        Long roadmapId = 5L;
        RoadmapTreeResDto tree = new RoadmapTreeResDto(sampleRoadmap(), List.of());
        when(queryRoadmapTreeService.execute(eq(userId), eq(userRole), eq(roadmapId))).thenReturn(Mono.just(tree));

        // Act
        Mono<RoadmapTreeResDto> result = controller.getRoadmapTree(userId, userRole, roadmapId);

        // Assert
        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        verify(queryRoadmapTreeService).execute(userId, userRole, roadmapId);
    }

    @Test
    void updateRoadmap_forwardsUserIdAndRoleHeadersToService() {
        // Arrange
        Long userId = 11L;
        String userRole = "ADMIN";
        Long roadmapId = 21L;
        UpdateRoadmapReqDto request = new UpdateRoadmapReqDto("new title", null, null);
        when(modifyRoadmapService.execute(eq(userId), eq(userRole), eq(roadmapId), any()))
                .thenReturn(Mono.just(sampleRoadmap()));

        // Act
        Mono<RoadmapResDto> result = controller.updateRoadmap(userId, userRole, roadmapId, request);

        // Assert
        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        verify(modifyRoadmapService).execute(userId, userRole, roadmapId, request);
    }

    @Test
    void deleteRoadmap_forwardsUserIdAndRoleHeadersToService() {
        // Arrange
        Long userId = 13L;
        String userRole = "MEMBER";
        Long roadmapId = 31L;
        when(deleteRoadmapService.execute(eq(userId), eq(userRole), eq(roadmapId))).thenReturn(Mono.empty());

        // Act
        Mono<ResponseEntity<Void>> result = controller.deleteRoadmap(userId, userRole, roadmapId);

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT))
                .verifyComplete();
        verify(deleteRoadmapService).execute(userId, userRole, roadmapId);
    }

    @Test
    void deleteRoadmap_doesNotSwallowUserRoleWhenItIsAnUnexpectedValue() {
        // Arrange: 헤더 값 검증/변환 없이 그대로 서비스로 전달되어야 한다 (컨트롤러는 검증하지 않음)
        Long userId = 1L;
        String userRole = "UNKNOWN_ROLE";
        Long roadmapId = 2L;
        when(deleteRoadmapService.execute(eq(userId), eq(userRole), eq(roadmapId))).thenReturn(Mono.empty());

        // Act
        controller.deleteRoadmap(userId, userRole, roadmapId).block();

        // Assert
        verify(deleteRoadmapService).execute(userId, "UNKNOWN_ROLE", roadmapId);
    }
}
