package com.cowork.project.github

import java.net.URI

data class GithubRepoRef(val owner: String, val repo: String)

/**
 * GitHub 저장소 URL을 owner/repo로 파싱한다.
 *
 * `cowork-chat`의 `ProjectClient.parseRepoUrl`과 동일한 규칙을 따른다:
 * `github.com` 호스트만 허용하고, repo 이름의 `.git` suffix는 제거한다.
 */
object GithubRepoUrlParser {

    fun parse(url: String): GithubRepoRef? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        if (uri.host != "github.com") return null

        val parts = (uri.path ?: "").split("/").filter { it.isNotBlank() }
        if (parts.size < 2) return null

        return GithubRepoRef(owner = parts[0], repo = parts[1].removeSuffix(".git"))
    }
}
