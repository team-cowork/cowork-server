package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubLabelResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class ListGithubLabelsServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var githubAppClient: GithubAppClient
        lateinit var service: ListGithubLabelsServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            callExecutor = mockk()
            githubAppClient = mockk()
            service = ListGithubLabelsServiceImpl(repoAccessResolver, callExecutor, githubAppClient)

            every { callExecutor.execute(any<() -> List<GithubLabelResDto>>()) } answers {
                firstArg<() -> List<GithubLabelResDto>>().invoke()
            }
        }

        describe("ListGithubLabelsServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("읽기 권한이 있는 경우") {
                    it("읽기 권한으로 레포를 해석하고 레포에 정의된 라벨 목록을 조회한다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        every { githubAppClient.listLabels("my-org", "my-repo") } returns
                            listOf(GithubLabelResDto("bug", "d73a4a"), GithubLabelResDto("enhancement", "a2eeef"))

                        val result = service.execute(7L, 1L, 5L)

                        result.map { it.name } shouldBe listOf("bug", "enhancement")
                        verify { repoAccessResolver.resolveForRead(7L, 1L, 5L) }
                    }
                }
            }
        }
    })
