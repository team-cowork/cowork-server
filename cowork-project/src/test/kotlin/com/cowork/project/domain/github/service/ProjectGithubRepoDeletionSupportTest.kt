package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.event.ProjectGithubRepoEventPublisher
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.githubPreference.event.GithubRepoSettingCommandPublisher
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant

class ProjectGithubRepoDeletionSupportTest :
    DescribeSpec({
        val repository = mockk<ProjectGithubRepoRepository>(relaxed = true)
        val eventPublisher = mockk<ProjectGithubRepoEventPublisher>(relaxed = true)
        val settingCommandPublisher = mockk<GithubRepoSettingCommandPublisher>(relaxed = true)
        val support = ProjectGithubRepoDeletionSupport(repository, eventPublisher, settingCommandPublisher)

        describe("deleteByProjectIds") {
            it("cascade 대상 repo links를 collect/delete하고 같은 version의 DELETE state를 모두 발행한다") {
                val occurredAt = Instant.parse("2026-08-27T00:00:00Z")
                val links = listOf(
                    ProjectGithubRepo(5L, 1L, 3L, "https://github.com/my-org/one"),
                    ProjectGithubRepo(6L, 2L, 3L, "https://github.com/my-org/two"),
                )
                every { repository.findAllByProjectIdInForUpdate(listOf(1L, 2L)) } returns links

                support.deleteByProjectIds(listOf(1L, 2L), occurredAt)

                verify(exactly = 1) { repository.deleteAll(links) }
                verify(exactly = 1) { eventPublisher.publishDelete(links[0], occurredAt) }
                verify(exactly = 1) { eventPublisher.publishDelete(links[1], occurredAt) }
                verify(exactly = 1) { settingCommandPublisher.publishDelete(5L, null, occurredAt) }
                verify(exactly = 1) { settingCommandPublisher.publishDelete(6L, null, occurredAt) }
            }
        }
    })
