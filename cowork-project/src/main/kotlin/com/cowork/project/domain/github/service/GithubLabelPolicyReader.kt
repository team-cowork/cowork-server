package com.cowork.project.domain.github.service

import com.cowork.project.global.client.PreferenceClient
import feign.FeignException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

private const val LABEL_AUTO_APPLY_KEY = "label_auto_apply"
private const val LABEL_AUTO_APPLY_DEFAULT = true

/**
 * cowork-preference의 GITHUB_REPO 리소스 설정에서 라벨 자동/수동 적용 정책을 읽는다.
 * 설정값이 아직 저장된 적이 없으면(row 없음) 기본값(자동 적용)으로 취급한다.
 */
@Component
class GithubLabelPolicyReader(private val preferenceClient: PreferenceClient) {

    fun readAutoApply(repoId: Long): Boolean = try {
        preferenceClient.getGithubRepoSettings(repoId)[LABEL_AUTO_APPLY_KEY] as? Boolean ?: LABEL_AUTO_APPLY_DEFAULT
    } catch (e: FeignException) {
        throw ExpectedException("설정 서비스와 통신 중 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY)
    }
}
