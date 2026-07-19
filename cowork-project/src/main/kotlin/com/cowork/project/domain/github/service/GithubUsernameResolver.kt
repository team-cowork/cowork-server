package com.cowork.project.domain.github.service

import com.cowork.project.global.client.UserClient
import feign.FeignException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

/**
 * `cowork-user`에서 사용자 프로필을 조회해 연동된 GitHub 사용자명을 해석한다.
 */
@Component
class GithubUsernameResolver(private val userClient: UserClient) {
    private val logger = LoggerFactory.getLogger(GithubUsernameResolver::class.java)

    fun resolve(userId: Long): String {
        val profile = try {
            userClient.getUserProfile(userId)
        } catch (e: FeignException.NotFound) {
            throw ExpectedException("사용자 정보를 찾을 수 없습니다.", HttpStatus.BAD_REQUEST)
        } catch (e: FeignException) {
            logger.error("Failed to call cowork-user for userId {}", userId, e)
            throw ExpectedException("사용자 정보 조회에 실패했습니다.", HttpStatus.BAD_GATEWAY)
        } catch (e: Exception) {
            logger.error("Unexpected error while calling cowork-user for userId {}", userId, e)
            throw ExpectedException("사용자 정보 조회에 실패했습니다.", HttpStatus.BAD_GATEWAY)
        }

        return profile.githubId
            ?: throw ExpectedException("GitHub 계정이 연동되어 있지 않습니다. 계정 설정에서 GitHub 계정을 연동해주세요.", HttpStatus.BAD_REQUEST)
    }
}
