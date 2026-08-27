package com.cowork.preference.router

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TeamRoleProjectionGateTest {

    @Test
    fun `gates every team role and assignment command`() {
        assertTrue(TeamRoleProjectionGate.requiresProjection("/internal/preferences/team/3/roles"))
        assertTrue(TeamRoleProjectionGate.requiresProjection("/internal/preferences/team/3/roles/7"))
        assertTrue(TeamRoleProjectionGate.requiresProjection("/internal/preferences/team/3/roles/7/members"))
    }

    @Test
    fun `does not gate liveness or unrelated preference routes`() {
        assertFalse(TeamRoleProjectionGate.requiresProjection("/health"))
        assertFalse(TeamRoleProjectionGate.requiresProjection("/health/ready"))
        assertFalse(TeamRoleProjectionGate.requiresProjection("/preferences/team/3"))
        assertFalse(TeamRoleProjectionGate.requiresProjection("/preferences/team/3/roles"))
    }
}
