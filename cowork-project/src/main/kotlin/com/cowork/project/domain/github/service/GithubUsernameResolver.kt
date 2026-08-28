package com.cowork.project.domain.github.service

import com.cowork.project.domain.user.service.UserProfileProjectionReader
import org.springframework.stereotype.Component

/**
 * `user.profile.event` local projection에서 연동된 GitHub 사용자명을 해석한다.
 */
@Component
class GithubUsernameResolver(private val profileReader: UserProfileProjectionReader) {
    fun resolve(userId: Long): String = profileReader.resolveGithubId(userId)
}
