package com.cowork.team

import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamVisibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TeamDomainTest {

    private fun team() = Team(name = "cowork", description = "협업 도구", visibility = TeamVisibility.PUBLIC, ownerId = 1)

    @Test
    fun `null로 전달한 항목은 기존 값을 유지한다`() {
        val team = team()

        team.update(null, null, TeamVisibility.PRIVATE)

        assertEquals("cowork", team.name)
        assertEquals("협업 도구", team.description)
        assertEquals(TeamVisibility.PRIVATE, team.visibility)
    }

    @Test
    fun `빈 설명으로 수정하면 설명을 비운다`() {
        val team = team()

        team.update(null, "", null)

        assertNull(team.description)
    }

    @Test
    fun `소유권을 이전하면 소유자 ID가 바뀐다`() {
        val team = team()

        team.transferOwnership(7)

        assertEquals(7, team.ownerId)
    }

    @Test
    fun `소유자와 관리자만 관리 역할로 본다`() {
        assertTrue(TeamMemberRole.OWNER.isManager)
        assertTrue(TeamMemberRole.ADMIN.isManager)
        assertFalse(TeamMemberRole.MEMBER.isManager)
    }
}
