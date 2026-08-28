package com.cowork.project.domain.github.service

import com.cowork.project.domain.user.service.UserProfileProjectionReader
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class GithubUsernameResolverTest :
    DescribeSpec({
        val profileReader = mockk<UserProfileProjectionReader>()
        val resolver = GithubUsernameResolver(profileReader)

        describe("resolve") {
            it("user profile projection의 githubId를 반환한다") {
                every { profileReader.resolveGithubId(7L) } returns "octocat"

                resolver.resolve(7L) shouldBe "octocat"
            }
        }
    })
