package com.cowork.project.github

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GithubRepoUrlParserTest {

    @Test
    fun `github_com URL을 owner repo로 파싱`() {
        val result = GithubRepoUrlParser.parse("https://github.com/my-org/my-repo")

        assertEquals(GithubRepoRef(owner = "my-org", repo = "my-repo"), result)
    }

    @Test
    fun `git suffix는 제거`() {
        val result = GithubRepoUrlParser.parse("https://github.com/my-org/my-repo.git")

        assertEquals(GithubRepoRef(owner = "my-org", repo = "my-repo"), result)
    }

    @Test
    fun `github_com이 아닌 호스트는 null`() {
        val result = GithubRepoUrlParser.parse("https://gitlab.com/my-org/my-repo")

        assertNull(result)
    }

    @Test
    fun `owner_repo 경로가 부족하면 null`() {
        val result = GithubRepoUrlParser.parse("https://github.com/my-org")

        assertNull(result)
    }

    @Test
    fun `유효하지 않은 URL 형식은 null`() {
        val result = GithubRepoUrlParser.parse("not a url")

        assertNull(result)
    }
}
