package com.cowork.team

import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamVisibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
