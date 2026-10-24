package com.cowork.team.domain.teamRole.operation

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

internal object TeamRoleResultJson {
    fun matches(
        stored: String?,
        incoming: String?,
        objectMapper: ObjectMapper,
        knownDefaultRole: Boolean = false,
    ): Boolean {
        if (stored == null || incoming == null) return stored == incoming
        val storedRole = objectMapper.readTree(stored) as? ObjectNode ?: return false
        // TODO(#596): 2026-11-01 05:00 KST 이후 아래 조건 완료를 확인하고 제거한다.
        // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
        // 저장 결과 보정과 구형 결과 재처리 종료 후 유형 보정만 제거하고 구조 비교는 유지한다.
        val storedDefault = storedRole.get("isDefault")
        if (storedDefault == null || (knownDefaultRole && storedDefault.isBoolean && !storedDefault.booleanValue())) {
            storedRole.put("isDefault", knownDefaultRole)
        }
        return storedRole == objectMapper.readTree(incoming)
    }
}
