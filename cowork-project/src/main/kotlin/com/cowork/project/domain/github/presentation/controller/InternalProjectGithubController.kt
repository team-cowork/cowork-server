package com.cowork.project.domain.github.presentation.controller

import com.cowork.project.domain.project.presentation.data.response.ProjectGithubLabelPolicyTargetResDto
import com.cowork.project.domain.project.presentation.data.response.ProjectGithubWebhookTargetResDto
import com.cowork.project.domain.project.service.QueryProjectGithubLabelPolicyTargetService
import com.cowork.project.domain.project.service.QueryProjectGithubWebhookTargetService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 게이트웨이를 거치지 않고 Eureka로 직접 호출되는 서비스 간 전용 엔드포인트.
 * `/internal` 하위 경로는 게이트웨이 라우트 테이블(`cowork-gateway-*.yml`)에 매칭되는 `Path=/api/...` 규칙이 없어
 * 외부에서 도달할 수 없다 (cowork-voice가 cowork-channel의 `/internal/channels/...`를 호출하는 것과 동일한 컨벤션).
 */
@Tag(name = "GitHub 웹훅 (내부 전용)", description = "cowork-chat이 Eureka로 직접 호출하는 GitHub 웹훅 알림 대상 조회 API")
@RestController
@RequestMapping("/internal/projects")
class InternalProjectGithubController(
    private val queryProjectGithubWebhookTargetService: QueryProjectGithubWebhookTargetService,
    private val queryProjectGithubLabelPolicyTargetService: QueryProjectGithubLabelPolicyTargetService,
) {

    @Operation(
        summary = "GitHub 웹훅 알림 대상 조회 (내부 서비스용)",
        description = "owner/repo에 연결되고 알림 채널이 설정된 레포를 전부 조회한다. cowork-chat이 사용한다. " +
            "서로 다른 팀이 같은 레포를 연결할 수 있어 0개 이상을 반환할 수 있다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공 (대상이 없으면 빈 배열)"),
    )
    @GetMapping("/github-webhook-target")
    fun getGithubWebhookTarget(
        @RequestParam owner: String,
        @RequestParam repo: String,
    ): List<ProjectGithubWebhookTargetResDto> = queryProjectGithubWebhookTargetService.execute(owner, repo)

    @Operation(
        summary = "GitHub 이슈 라벨 자동/수동 정책 조회 (내부 서비스용)",
        description = "owner/repo에 연결된 레포의 라벨 정책을 전부 조회한다. cowork-github-app이 사용한다. " +
            "서로 다른 팀이 같은 레포를 연결할 수 있어 0개 이상을 반환할 수 있다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공 (대상이 없으면 빈 배열)"),
    )
    @GetMapping("/github-repos/label-policy")
    fun getLabelPolicy(
        @RequestParam owner: String,
        @RequestParam repo: String,
    ): List<ProjectGithubLabelPolicyTargetResDto> = queryProjectGithubLabelPolicyTargetService.execute(owner, repo)
}
