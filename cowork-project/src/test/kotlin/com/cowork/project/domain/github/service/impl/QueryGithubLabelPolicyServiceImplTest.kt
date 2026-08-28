package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.service.GithubLabelPolicyReader
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class QueryGithubLabelPolicyServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var labelPolicyReader: GithubLabelPolicyReader
        lateinit var service: QueryGithubLabelPolicyServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            labelPolicyReader = mockk()
            service = QueryGithubLabelPolicyServiceImpl(repoAccessResolver, labelPolicyReader)
        }

        describe("QueryGithubLabelPolicyServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("읽기 권한이 있는 경우") {
                    it("레포 접근을 검증하고 라벨 정책을 반환한다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        every { labelPolicyReader.readAutoApply(5L) } returns false

                        val result = service.execute(7L, 1L, 5L)

                        result.autoApply shouldBe false
                    }
                }
            }
        }
    })
