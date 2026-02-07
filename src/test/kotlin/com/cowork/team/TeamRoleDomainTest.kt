package com.cowork.team

import com.cowork.team.domain.TeamPermission
import com.cowork.team.domain.TeamPermissionsConverter
import com.cowork.team.domain.TeamRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TeamRoleDomainTest {

    private val converter = TeamPermissionsConverter()

    @Test
    fun `권한 목록은 정의 순서대로 쉼표로 이어 저장한다`() {
        val column = converter.convertToDatabaseColumn(setOf(TeamPermission.MENTION_EVERYONE, TeamPermission.MANAGE_TEAM))

        assertEquals("MANAGE_TEAM,MENTION_EVERYONE", column)
    }

    @Test
    fun `알 수 없는 권한 이름과 빈 값은 무시한다`() {
        val permissions = converter.convertToEntityAttribute("MANAGE_TEAM, ,REMOVED_PERMISSION,KICK_MEMBERS")

        assertEquals(setOf(TeamPermission.MANAGE_TEAM, TeamPermission.KICK_MEMBERS), permissions)
    }

    @Test
    fun `null 컬럼은 빈 권한 목록으로 읽는다`() {
        assertEquals(emptySet<TeamPermission>(), converter.convertToEntityAttribute(null))
        assertEquals("", converter.convertToDatabaseColumn(null))
    }

    @Test
    fun `전달한 항목만 수정한다`() {
        val role = TeamRole(teamId = 1, name = "디자인", colorHex = "#FFFFFF", priority = 1)

        role.update(null, "#000000", null, true, null)

        assertEquals("디자인", role.name)
        assertEquals("#000000", role.colorHex)
        assertEquals(1, role.priority)
        assertEquals(true, role.mentionable)
    }
}
