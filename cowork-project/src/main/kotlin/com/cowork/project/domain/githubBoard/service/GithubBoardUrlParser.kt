package com.cowork.project.domain.githubBoard.service

import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.net.URI
import java.util.Locale

data class GithubBoardRef(val owner: String, val projectNumber: Int)

/**
 * GitHub Project URL을 해석한다. GitHub App 설치 토큰으로는 개인 계정이 소유한 Project를 다룰 수 없어
 * `https://github.com/orgs/{org}/projects/{number}` 형식만 받는다. 뷰 경로와 쿼리는 무시한다.
 */
object GithubBoardUrlParser {
    private val ORG_PATH = Regex("^/orgs/([A-Za-z0-9-]{1,39})/projects/(\\d{1,9})(?:/.*)?$")
    private val USER_PATH = Regex("^/users/[^/]+/projects/\\d+(?:/.*)?$")

    fun parse(url: String): GithubBoardRef {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT)
        val path = uri?.path.orEmpty().trimEnd('/')
        if (host != "github.com" && host != "www.github.com") throw invalid()
        if (USER_PATH.matches(path)) {
            throw ExpectedException("개인 계정이 소유한 GitHub Project는 연결할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        val match = ORG_PATH.matchEntire(path) ?: throw invalid()
        val number = match.groupValues[2].toInt()
        if (number <= 0) throw invalid()
        return GithubBoardRef(match.groupValues[1].lowercase(Locale.ROOT), number)
    }

    private fun invalid() = ExpectedException("유효하지 않은 GitHub Project URL입니다.", HttpStatus.BAD_REQUEST)
}
