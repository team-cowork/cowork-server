package com.cowork.team.global.consumer

import com.cowork.team.domain.team.projection.TeamSettingProjection
import com.cowork.team.domain.team.projection.TeamSettingProjectionRepository
import com.cowork.team.global.projection.toProjectionPrecision
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PreferenceTeamSettingProjectionHandler(private val repository: TeamSettingProjectionRepository) {

    /**
     * Preference snapshot은 저장된 version을 그대로 다시 보내면서 새로 추가된 필드를 채울 수 있으므로
     * 같은 version도 다시 적용한다. 닉네임 형식 필드가 없는 과거 record는 안내를 끈 것으로 본다.
     */
    @Transactional
    fun handle(event: PreferenceTeamSettingEvent) {
        val occurredAt = event.occurredAt.toProjectionPrecision()
        val existing = repository.findById(event.teamId).orElse(null)
        if (existing?.sourceOccurredAt?.toProjectionPrecision()?.isAfter(occurredAt) == true) return

        val example = event.nicknameFormatExample?.takeIf { it.isNotBlank() }
        val setting = existing ?: TeamSettingProjection(teamId = event.teamId, sourceOccurredAt = occurredAt)
        setting.applyUpsert(
            nicknameFormatEnforced = event.nicknameFormatEnforced == true && example != null,
            nicknameFormatExample = example,
            occurredAt = occurredAt,
        )
        repository.save(setting)
    }
}
