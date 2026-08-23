package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.presentation.data.request.UpdateGithubLabelPolicyReqDto
import com.cowork.project.domain.github.service.GithubLabelPolicyReader
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class UpdateGithubLabelPolicyServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var labelPolicyReader: GithubLabelPolicyReader
        lateinit var service: UpdateGithubLabelPolicyServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            labelPolicyReader = mockk()
            service = UpdateGithubLabelPolicyServiceImpl(repoAccessResolver, labelPolicyReader)
        }

        describe("UpdateGithubLabelPolicyServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("수정 권한이 있는 경우") {
                    it("수정 권한을 검증하고 cowork-preference에 설정을 반영한다") {
                        every { repoAccessResolver.resolveForModify(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        every { labelPolicyReader.writeAutoApply(5L, false) } returns false

                        val result = service.execute(7L, 1L, 5L, UpdateGithubLabelPolicyReqDto(autoApply = false))

                        result.autoApply shouldBe false
                        verify { repoAccessResolver.resolveForModify(7L, 1L, 5L) }
                    }
                }
            }
        }
    })
