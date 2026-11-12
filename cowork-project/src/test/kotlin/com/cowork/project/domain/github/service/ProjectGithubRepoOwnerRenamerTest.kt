package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.event.ProjectGithubRepoEventPublisher
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class ProjectGithubRepoOwnerRenamerTest :
    DescribeSpec({

        lateinit var repoLinkRepository: ProjectGithubRepoRepository
        lateinit var repoEventPublisher: ProjectGithubRepoEventPublisher
        lateinit var renamer: ProjectGithubRepoOwnerRenamer

        beforeEach {
            repoLinkRepository = mockk()
            repoEventPublisher = mockk(relaxed = true)
            renamer = ProjectGithubRepoOwnerRenamer(repoLinkRepository, repoEventPublisher)
            every { repoLinkRepository.save(any()) } answers { firstArg() }
        }

        fun repoLink(id: Long, url: String) = ProjectGithubRepo(id = id, projectId = 10L, teamId = 1L, githubRepoUrl = url)

        describe("ProjectGithubRepoOwnerRenamer 클래스의") {

            describe("rename 메서드는") {
                context("옛 조직 이름으로 저장된 링크가 있으면") {
                    it("URL owner를 새 이름으로 바꾸고 이벤트를 다시 발행한다") {
                        val link = repoLink(1L, "https://github.com/Old-Org/api")
                        every { repoLinkRepository.findAllByTeamIdForUpdate(1L) } returns listOf(link)

                        renamer.rename(teamId = 1L, previousOwner = "old-org", newOwner = "new-org")

                        link.githubRepoUrl shouldBe "https://github.com/new-org/api"
                        verify(exactly = 1) { repoLinkRepository.save(link) }
                        verify(exactly = 1) { repoEventPublisher.publishUpsert(link, any()) }
                    }
                }

                context("owner가 옛 조직 이름이 아닌 링크는") {
                    it("바꾸지 않는다") {
                        val link = repoLink(1L, "https://github.com/other-org/api")
                        every { repoLinkRepository.findAllByTeamIdForUpdate(1L) } returns listOf(link)

                        renamer.rename(teamId = 1L, previousOwner = "old-org", newOwner = "new-org")

                        link.githubRepoUrl shouldBe "https://github.com/other-org/api"
                        verify(exactly = 0) { repoLinkRepository.save(any()) }
                        verify(exactly = 0) { repoEventPublisher.publishUpsert(any(), any()) }
                    }
                }

                context("새 조직 이름으로 같은 레포가 이미 연결돼 있으면") {
                    it("옛 링크를 그대로 두고 나머지 링크만 바꾼다") {
                        val oldLink = repoLink(1L, "https://github.com/old-org/api")
                        val relinked = repoLink(2L, "https://github.com/New-Org/API")
                        val otherLink = repoLink(3L, "https://github.com/old-org/web")
                        every { repoLinkRepository.findAllByTeamIdForUpdate(1L) } returns
                            listOf(oldLink, relinked, otherLink)

                        renamer.rename(teamId = 1L, previousOwner = "old-org", newOwner = "new-org")

                        oldLink.githubRepoUrl shouldBe "https://github.com/old-org/api"
                        otherLink.githubRepoUrl shouldBe "https://github.com/new-org/web"
                        verify(exactly = 0) { repoEventPublisher.publishUpsert(oldLink, any()) }
                        verify(exactly = 1) { repoEventPublisher.publishUpsert(otherLink, any()) }
                    }
                }

                context("조직 이름의 대소문자만 바뀌면") {
                    it("링크를 바꾸지 않는다") {
                        val link = repoLink(1L, "https://github.com/old-org/api")
                        every { repoLinkRepository.findAllByTeamIdForUpdate(1L) } returns listOf(link)

                        renamer.rename(teamId = 1L, previousOwner = "old-org", newOwner = "Old-Org")

                        link.githubRepoUrl shouldBe "https://github.com/old-org/api"
                        verify(exactly = 0) { repoEventPublisher.publishUpsert(any(), any()) }
                    }
                }
            }
        }
    })
