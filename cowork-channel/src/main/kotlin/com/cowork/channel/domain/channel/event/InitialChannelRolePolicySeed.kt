package com.cowork.channel.domain.channel.event

import java.time.Instant

data class InitialChannelRolePolicySeed(
    val actorId: Long,
    val actorMembershipVersion: Instant,
    val overwrites: List<InitialChannelRoleOverwrite>,
    // TODO(#596): 2026-11-01 05:00 KST 이후 구형 producer 종료·legacy seed ledger 처리와 snapshot 확인 후 null 호환 제거.
    // 저장된 불변 NULL seed는 재승인 없이 건너뛰어야 하며, 역직렬화 실패로 lifecycle을 격리하지 않는다.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    val actorAuthorizationVersion: Instant? = null,
)

data class InitialChannelRoleOverwrite(val roleId: Long, val permissions: Map<String, Boolean>)
