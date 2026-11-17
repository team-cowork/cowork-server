package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.github.entity.TeamGithubInstallation
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import com.cowork.project.domain.githubBoard.repository.ProjectGithubBoardRepository
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

class GithubBoardAccessResolverTest :
    DescribeSpec({
        val project = Project(id = 3L, teamId = 1L, name = "p", description = null, createdBy = 10L)
        lateinit var guard: ProjectAccessGuard
        lateinit var boards: ProjectGithubBoardRepository
        lateinit var installations: TeamGithubInstallationRepository
        lateinit var resolver: GithubBoardAccessResolver

        beforeEach {
            guard = mockk(relaxed = true)
            boards = mockk()
            installations = mockk()
            resolver = GithubBoardAccessResolver(guard, boards, installations)
            every { guard.findProjectOrThrow(3L) } returns project
            every { installations.findById(1L) } returns
                Optional.of(TeamGithubInstallation(teamId = 1L, installationId = 10L, orgLogin = "My-Org"))
        }

        fun board(owner: String) = ProjectGithubBoard(
            id = 7L,
            projectId = 3L,
            teamId = 1L,
            ownerLogin = owner,
            projectNumber = 2,
            createdBy = 10L,
        )

        describe("GithubBoardAccessResolver는") {
            it("팀에 연결된 조직의 Project만 허용하며 대소문자는 구분하지 않는다") {
                resolver.requireTeamOrg(1L, "my-org")

                shouldThrow<ExpectedException> { resolver.requireTeamOrg(1L, "other-org") }
                    .statusCode shouldBe HttpStatus.FORBIDDEN
            }

            it("팀에 설치한 조직이 없으면 거부한다") {
                every { installations.findById(2L) } returns Optional.empty()

                shouldThrow<ExpectedException> { resolver.requireTeamOrg(2L, "my-org") }
            }

            it("보기는 보드 보기 권한을, 카드 이동은 보드 수정 권한을 검사한다") {
                every { boards.findByIdAndProjectId(7L, 3L) } returns board("my-org")

                resolver.resolve(20L, 3L, 7L, GithubBoardAccess.READ)
                resolver.resolve(20L, 3L, 7L, GithubBoardAccess.WRITE)

                verify { guard.requireBoardReader(project, 20L) }
                verify { guard.requireBoardModifier(project, 20L) }
            }

            it("권한이 없으면 연결을 찾기 전에 거부한다") {
                every { guard.requireBoardModifier(project, 20L) } throws
                    ExpectedException("GitHub 보드 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)

                shouldThrow<ExpectedException> { resolver.resolve(20L, 3L, 7L, GithubBoardAccess.WRITE) }

                verify(exactly = 0) { boards.findByIdAndProjectId(any(), any()) }
            }

            it("조직 이름이 바뀌어 팀 조직과 다른 연결은 거부한다") {
                every { boards.findByIdAndProjectId(7L, 3L) } returns board("old-org")

                shouldThrow<ExpectedException> { resolver.resolve(20L, 3L, 7L, GithubBoardAccess.READ) }
                    .statusCode shouldBe HttpStatus.FORBIDDEN
            }
        }
    })
