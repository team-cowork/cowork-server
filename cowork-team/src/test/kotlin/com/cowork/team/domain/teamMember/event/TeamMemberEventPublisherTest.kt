package com.cowork.team.domain.teamMember.event

import com.cowork.team.domain.teamAccess.LockedTeam
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.entity.TeamMemberEventState
import com.cowork.team.global.outbox.OutboxWriter
import com.cowork.team.global.outbox.Topics
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class TeamMemberEventPublisherTest :
    DescribeSpec({
        describe("팀원 스냅샷") {
            it("마지막 변경 시각과 별도로 원본 멤버의 가입 시각을 발행한다") {
                val outbox = mockk<OutboxWriter>(relaxed = true)
                val publisher = TeamMemberEventPublisher(mockk(), mockk(), mockk(), mockk(relaxed = true), outbox)
                val joinedAt = Instant.parse("2026-11-20T03:00:00Z")
                val changedAt = joinedAt.plusSeconds(3600)
                val team = LockedTeam(id = 1L, name = "team", ownerId = 2L)
                val member = TeamMember(teamId = team.id, userId = 3L)
                val state = TeamMemberEventState.create(team, member, false, changedAt)

                publisher.publishSnapshot(state, LocalDateTime.ofInstant(joinedAt, ZoneId.systemDefault()))

                val event = slot<TeamMemberEvent>()
                verify { outbox.enqueue(Topics.TEAM_MEMBER_EVENT, "1:3", capture(event)) }
                event.captured.joinedAt shouldBe joinedAt
                event.captured.occurredAt shouldBe changedAt
            }
        }
    })
