package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.github.service.GithubSyncRequestScheduler
import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import com.cowork.project.domain.githubBoard.presentation.data.request.AddProjectGithubBoardReqDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardSyncStatus
import com.cowork.project.domain.githubBoard.repository.GithubProjectProjectionRepository
import com.cowork.project.domain.githubBoard.repository.ProjectGithubBoardRepository
import com.cowork.project.domain.githubBoard.service.impl.AddProjectGithubBoardServiceImpl
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.service.ProjectAccessGuard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class AddProjectGithubBoardServiceImplTest :
    DescribeSpec({
        val project = Project(id = 3L, teamId = 1L, name = "p", description = null, createdBy = 10L)
        lateinit var guard: ProjectAccessGuard
        lateinit var resolver: GithubBoardAccessResolver
        lateinit var boards: ProjectGithubBoardRepository
        lateinit var scheduler: GithubSyncRequestScheduler
        lateinit var service: AddProjectGithubBoardServiceImpl

        beforeEach {
            guard = mockk(relaxed = true)
            resolver = mockk(relaxed = true)
            boards = mockk()
            scheduler = mockk(relaxed = true)
            val projections = mockk<GithubProjectProjectionRepository>()
            every { projections.findById(any()) } returns Optional.empty()
            service = AddProjectGithubBoardServiceImpl(guard, resolver, boards, projections, scheduler)
            every { guard.findProjectForUpdateOrThrow(3L) } returns project
            every { boards.existsByProjectIdAndOwnerLoginAndProjectNumber(3L, "my-org", 5) } returns false
            every { boards.saveAndFlush(any()) } answers { firstArg<ProjectGithubBoard>() }
        }

        describe("AddProjectGithubBoardServiceImpl은") {
            it("조직 소유 Project를 소문자 조직으로 저장하고 동기화를 요청한다") {
                val result = service.execute(
                    20L,
                    3L,
                    AddProjectGithubBoardReqDto("https://github.com/orgs/My-Org/projects/5"),
                )

                result.owner shouldBe "my-org"
                result.syncStatus shouldBe GithubBoardSyncStatus.SYNCING
                verify { guard.requireGithubManager(project, 20L) }
                verify { resolver.requireTeamOrg(1L, "my-org") }
                verify { scheduler.requestProjectSync("my-org", 5, force = false) }
            }

            it("다른 조직의 Project는 저장하지 않는다") {
                every { resolver.requireTeamOrg(1L, "my-org") } throws
                    ExpectedException("팀에 연결된 GitHub 조직의 Project만 사용할 수 있습니다.", HttpStatus.FORBIDDEN)

                shouldThrow<ExpectedException> {
                    service.execute(20L, 3L, AddProjectGithubBoardReqDto("https://github.com/orgs/my-org/projects/5"))
                }
                verify(exactly = 0) { boards.saveAndFlush(any()) }
            }

            it("이미 연결된 Project는 409로 거부한다") {
                every { boards.existsByProjectIdAndOwnerLoginAndProjectNumber(3L, "my-org", 5) } returns true

                shouldThrow<ExpectedException> {
                    service.execute(20L, 3L, AddProjectGithubBoardReqDto("https://github.com/orgs/my-org/projects/5"))
                }.statusCode shouldBe HttpStatus.CONFLICT
            }

            it("GitHub 관리 권한이 없으면 URL을 해석하기 전에 거부한다") {
                every { guard.requireGithubManager(project, 20L) } throws
                    ExpectedException("팀 관리 권한이 없습니다.", HttpStatus.FORBIDDEN)

                shouldThrow<ExpectedException> {
                    service.execute(20L, 3L, AddProjectGithubBoardReqDto("https://github.com/users/octocat/projects/1"))
                }.statusCode shouldBe HttpStatus.FORBIDDEN
            }
        }
    })
