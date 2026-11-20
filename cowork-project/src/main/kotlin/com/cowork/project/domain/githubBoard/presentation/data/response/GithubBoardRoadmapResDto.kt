package com.cowork.project.domain.githubBoard.presentation.data.response

import com.cowork.project.domain.githubBoard.model.GithubBoardRoadmapDateUnit
import com.cowork.project.domain.githubBoard.model.GithubBoardRoadmapSettingsSource
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

data class GithubBoardSpanResDto(
    @field:Schema(description = "구간 시작일", example = "2026-11-15")
    val start: LocalDate,
    @field:Schema(description = "구간 종료일. 이 날짜를 포함합니다.", example = "2026-11-21")
    val end: LocalDate,
)

data class GithubBoardRoadmapItemResDto(
    @field:Schema(description = "카드")
    val item: GithubBoardItemResDto,
    @field:Schema(description = "타임라인 구간")
    val span: GithubBoardSpanResDto,
)

data class GithubBoardRoadmapGroupResDto(
    @field:Schema(
        description = "그룹 ID. 옵션 ID, iteration ID 또는 field 값입니다. null은 값 없는 그룹이거나 그룹 기준이 없을 때의 전체 그룹입니다.",
        nullable = true,
    )
    val id: String?,
    @field:Schema(description = "그룹 이름", example = "In Progress")
    val name: String,
    @field:Schema(description = "옵션 색상", example = "YELLOW", nullable = true)
    val color: String?,
    @field:Schema(description = "그룹 안 일정이 있는 카드 수", example = "3")
    val itemCount: Int,
    @field:Schema(description = "그룹 안 일정이 있는 카드. 뷰 정렬 순서이며 정렬이 없으면 GitHub 위치 순서")
    val items: List<GithubBoardRoadmapItemResDto>,
)

data class GithubBoardRoadmapResDto(
    @field:Schema(description = "조회한 Roadmap 뷰")
    val view: GithubBoardViewResDto,
    @field:Schema(description = "구간 시작일을 정하는 date 또는 iteration field")
    val startField: GithubBoardFieldResDto,
    @field:Schema(description = "구간 종료일을 정하는 date 또는 iteration field")
    val endField: GithubBoardFieldResDto,
    @field:Schema(
        description = "타임라인 날짜 단위. GitHub API가 뷰의 확대 단위를 주지 않아 MONTH를 돌려줍니다.",
        example = "MONTH",
    )
    val dateUnit: GithubBoardRoadmapDateUnit,
    @field:Schema(
        description = "시작 field와 종료 field를 정한 곳. REQUEST는 요청 파라미터, DEFAULT는 서버 기본 규칙입니다. " +
            "GitHub API는 뷰에 저장한 시작 field와 종료 field를 주지 않습니다.",
        example = "DEFAULT",
    )
    val settingsSource: GithubBoardRoadmapSettingsSource,
    @field:Schema(description = "뷰에 저장된 그룹 field", nullable = true)
    val groupByField: GithubBoardFieldResDto?,
    @field:Schema(
        description = "그룹 field로 나눌 수 있는지 여부. 담당자나 라벨처럼 값이 여러 개인 field이면 false이며 모든 카드를 그룹 하나에 담습니다.",
        example = "true",
    )
    val groupSupported: Boolean,
    @field:Schema(
        description = "그룹. 값 없는 그룹이 맨 앞이고 이후 옵션 순서, iteration 시작일 순서 또는 값의 오름차순입니다. 그룹 field가 없으면 모든 카드를 담은 그룹 하나입니다.",
    )
    val groups: List<GithubBoardRoadmapGroupResDto>,
    @field:Schema(description = "시작 field와 종료 field가 모두 비어 있어 미배정 영역에 그릴 카드. 뷰 정렬 순서")
    val unscheduledItems: List<GithubBoardItemResDto>,
    @field:Schema(description = "서버가 해석해 적용한 뷰 filter")
    val appliedFilter: GithubBoardAppliedFilterResDto,
    @field:Schema(description = "해석하지 못해 적용하지 않은 뷰 filter 토큰", example = "[\"reason:completed\"]")
    val unsupportedFilterTokens: List<String>,
)
