package com.cowork.preference.domain

import java.time.Instant

data class TeamMemberProjection(
    val teamId: Long,
    val accountId: Long,
    val builtInRole: String,
    val deleted: Boolean,
    val sourceOccurredAt: Instant,
    val authorizationVersion: Instant? = null,
) {
    // TODO(#596): 2026-11-01 05:00 KST 이후 구버전 producer 중단·snapshot 보정·구형 재처리 종료 후 fallback을 제거한다.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    val effectiveAuthorizationVersion: Instant get() = authorizationVersion ?: sourceOccurredAt
}
