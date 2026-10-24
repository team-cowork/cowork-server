package com.cowork.team.domain.team.projection

import com.cowork.team.global.projection.ProjectionReadinessGate
import org.springframework.stereotype.Component

data class NicknameFormatGuide(val enforced: Boolean, val example: String?)

@Component
class TeamSettingProjectionReader(
    private val repository: TeamSettingProjectionRepository,
    private val projectionReadinessGate: ProjectionReadinessGate,
) {
    /** 팀 설정을 저장한 적이 없는 팀은 preference state가 없으므로 안내를 끈 것으로 본다. */
    fun getNicknameFormat(teamId: Long): NicknameFormatGuide {
        projectionReadinessGate.requireReady()
        val setting = repository.findById(teamId).orElse(null)
        return NicknameFormatGuide(
            enforced = setting?.nicknameFormatEnforced == true,
            example = setting?.nicknameFormatExample,
        )
    }
}
