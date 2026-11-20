package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GithubBoardRepositoryResolverTest :
    DescribeSpec({
        val board = ProjectGithubBoard(7L, 3L, 1L, "my-org", 2, createdBy = 10L)
        lateinit var repository: ProjectGithubRepoRepository
        lateinit var resolver: GithubBoardRepositoryResolver
        beforeEach {
            repository = mockk()
            resolver = GithubBoardRepositoryResolver(repository)
        }

        describe("GithubBoardRepositoryResolver 클래스의 resolve 메서드는") {
            context("같은 프로젝트에 연결된 팀 조직의 저장소이면") {
                it("조직명의 대소문자를 무시하고 저장소를 반환한다") {
                    every { repository.findByIdAndProjectId(5L, 3L) } returns
                        ProjectGithubRepo(5L, 3L, 1L, "https://github.com/My-Org/app")
                    resolver.resolve(board, 5L) shouldBe GithubRepoRef("My-Org", "app")
                }
            }
            context("다른 프로젝트 또는 조직의 저장소이면") {
                it("다른 프로젝트의 연결 ID를 거부한다") {
                    every { repository.findByIdAndProjectId(5L, 3L) } returns null
                    shouldThrow<ExpectedException> { resolver.resolve(board, 5L) }.statusCode shouldBe
                        HttpStatus.NOT_FOUND
                }
                it("팀 조직 밖의 저장소를 거부한다") {
                    every { repository.findByIdAndProjectId(5L, 3L) } returns
                        ProjectGithubRepo(5L, 3L, 1L, "https://github.com/other-org/app")
                    shouldThrow<ExpectedException> { resolver.resolve(board, 5L) }.statusCode shouldBe
                        HttpStatus.FORBIDDEN
                }
            }
        }

        describe("GithubBoardRepositoryResolver 클래스의 resolveContent 메서드는") {
            context("이슈 카드의 저장소를 확인할 때") {
                it("프로젝트에 연결된 저장소를 대소문자와 무관하게 찾는다") {
                    every { repository.findAllByProjectId(3L) } returns
                        listOf(ProjectGithubRepo(5L, 3L, 1L, "https://github.com/My-Org/App.git"))
                    resolver.resolveContent(board, "my-org/app") shouldBe GithubRepoRef("my-org", "app")
                }
                it("같은 조직이어도 연결되지 않은 저장소는 거부한다") {
                    every { repository.findAllByProjectId(3L) } returns emptyList()
                    shouldThrow<ExpectedException> { resolver.resolveContent(board, "my-org/private") }
                        .statusCode shouldBe HttpStatus.FORBIDDEN
                }
                it("다른 조직의 이슈 담당자 변경을 거부한다") {
                    shouldThrow<ExpectedException> { resolver.resolveContent(board, "other-org/app") }
                        .statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }
        }
    })
