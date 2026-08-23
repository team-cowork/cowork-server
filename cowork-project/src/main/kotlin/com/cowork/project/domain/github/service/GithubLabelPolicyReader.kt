package com.cowork.project.domain.github.service

import com.cowork.project.global.client.PreferenceClient
import feign.FeignException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

private const val LABEL_AUTO_APPLY_KEY = "label_auto_apply"
private const val LABEL_AUTO_APPLY_DEFAULT = true

/**
 * cowork-preference의 GITHUB_REPO 리소스 설정에서 라벨 자동/수동 적용 정책을 읽고 쓴다.
 * 조회 시 설정값이 아직 저장된 적이 없으면(row 없음) 기본값(자동 적용)으로 취급한다.
 */
@Component
class GithubLabelPolicyReader(private val preferenceClient: PreferenceClient) {
    private val logger = LoggerFactory.getLogger(GithubLabelPolicyReader::class.java)

    fun readAutoApply(repoId: Long): Boolean = callPreference(repoId) {
        preferenceClient.getGithubRepoSettings(repoId)[LABEL_AUTO_APPLY_KEY] as? Boolean ?: LABEL_AUTO_APPLY_DEFAULT
    }

    fun writeAutoApply(repoId: Long, autoApply: Boolean): Boolean = callPreference(repoId) {
        val updated = preferenceClient.updateGithubRepoSettings(repoId, mapOf(LABEL_AUTO_APPLY_KEY to autoApply))
        updated[LABEL_AUTO_APPLY_KEY] as? Boolean
            ?: throw ExpectedException("설정 저장에 실패했습니다.", HttpStatus.BAD_GATEWAY)
    }

    private fun <T> callPreference(repoId: Long, call: () -> T): T = try {
        call()
    } catch (e: ExpectedException) {
        throw e
    } catch (e: FeignException) {
        logger.error("Failed to call cowork-preference for repoId {}", repoId, e)
        throw ExpectedException("설정 서비스와 통신 중 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY)
    } catch (e: Exception) {
        logger.error("Unexpected error while calling cowork-preference for repoId {}", repoId, e)
        throw ExpectedException("설정 서비스와 통신 중 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY)
    }
}
