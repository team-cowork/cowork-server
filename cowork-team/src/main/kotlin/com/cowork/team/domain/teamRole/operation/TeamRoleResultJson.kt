package com.cowork.team.domain.teamRole.operation

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

internal object TeamRoleResultJson {
    fun matches(stored: String?, incoming: String?, objectMapper: ObjectMapper): Boolean {
        if (stored == null || incoming == null) return stored == incoming
        val storedRole = objectMapper.readTree(stored) as? ObjectNode ?: return false
        return storedRole == objectMapper.readTree(incoming)
    }
}
