package com.cowork.roadmap.global.team.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class RolePolicyStateTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void roleAndAssignmentKeysAreBoundToScope() {
        var grant = mapper.readTree("""
                {"eventType":"ASSIGNMENT","roleId":5,"accountId":7,"deleted":true,"occurredAt":"2026-11-19T00:00:00Z"}
                """);
        assertTrue(RolePolicyState.parse("assignment:7:5", grant, false).deleted());
        assertThrows(IllegalArgumentException.class, () -> RolePolicyState.parse("assignment:8:5", grant, false));
        assertThrows(IllegalArgumentException.class, () -> RolePolicyState.parse("assignment:1:7:5", grant, true));
    }

    @Test
    void membershipCleanupRetainsTeamAndAccountFence() {
        var removal = mapper.readTree("""
                {"eventType":"MEMBER_ASSIGNMENTS_DELETED","teamId":9,"accountId":7,"occurredAt":"2026-11-19T00:00:00Z"}
                """);
        var fence = RolePolicyState.parse("member:9:7", removal, true);
        assertEquals("MEMBER", fence.kind());
        assertTrue(fence.deleted());
        assertThrows(IllegalArgumentException.class, () -> RolePolicyState.parse("member:8:7", removal, true));
    }

    @Test
    void nonBooleanPermissionCannotGrantAccess() {
        var role = mapper.readTree("""
                {"eventType":"ROLE","roleId":5,"isDefault":false,"permissions":{"manage_global_roadmaps":"true"},
                 "deleted":false,"occurredAt":"2026-11-19T00:00:00Z"}
                """);
        assertThrows(IllegalArgumentException.class, () -> RolePolicyState.parse("role:5", role, false));
    }
}
