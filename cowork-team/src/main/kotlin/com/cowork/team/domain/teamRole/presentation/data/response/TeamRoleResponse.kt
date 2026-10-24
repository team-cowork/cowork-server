package com.cowork.team.domain.teamRole.presentation.data.response

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

data class TeamRoleResponse(
    @field:Schema(description = "역할 ID", example = "1")
    val id: Long,
    @field:Schema(description = "팀 ID", example = "1")
    val teamId: Long,
    @field:Schema(description = "역할 이름", example = "Frontend")
    val name: String,
    @field:Schema(description = "역할 색상 HEX", example = "#5865F2")
    val colorHex: String,
    @field:Schema(description = "역할 우선순위. 숫자가 높을수록 상위 역할", example = "10")
    val priority: Int,
    @field:Schema(description = "역할 멘션 허용 여부", example = "true")
    val mentionable: Boolean,
    @field:Schema(description = "역할 기본 권한. 모든 권한 키의 boolean 전체 상태")
    val permissions: Map<String, Boolean>,
    @field:Schema(description = "생성 일시")
    val createdAt: String?,
    @field:Schema(description = "수정 일시")
    val updatedAt: String?,
    @field:Schema(description = "활성 팀 멤버 모두에게 자동 적용되는 기본 역할 여부")
    // TODO(#596): 2026-11-01 05:00 KST 이후 아래 조건 완료를 확인하고 제거한다.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    // 저장 작업 결과 보정 완료와 구형 결과 재처리 종료 확인 후 역직렬화 기본값을 제거한다.
    @field:JsonProperty("isDefault")
    val isDefault: Boolean = false,
)
