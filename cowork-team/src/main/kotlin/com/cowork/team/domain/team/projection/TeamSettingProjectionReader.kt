package com.cowork.team.domain.team.projection

import com.cowork.team.global.projection.ProjectionReadinessState
import org.springframework.stereotype.Component

data class NicknameFormatGuide(val enforced: Boolean, val example: String?) {
    companion object {
        val DISABLED = NicknameFormatGuide(enforced = false, example = null)
    }
}

@Component
class TeamSettingProjectionReader(
    private val repository: TeamSettingProjectionRepository,
    private val projectionReadinessState: ProjectionReadinessState,
) {
    /**
     * 닉네임 형식은 서버가 판정하지 않는 안내용 값이므로 projection 동기화가 끝나지 않았다고 가입을 막지 않는다.
     * 동기화 중이거나 팀 설정을 저장한 적이 없는 팀은 안내를 끈 것으로 본다.
     */
    fun getNicknameFormat(teamId: Long): NicknameFormatGuide {
        if (!projectionReadinessState.isReady()) return NicknameFormatGuide.DISABLED
        val setting = repository.findById(teamId).orElse(null) ?: return NicknameFormatGuide.DISABLED
        return NicknameFormatGuide(
            enforced = setting.nicknameFormatEnforced,
            example = setting.nicknameFormatExample,
        )
    }
}
