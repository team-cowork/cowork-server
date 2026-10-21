package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.GithubOrgBackfillStatus
import com.cowork.project.domain.github.event.GITHUB_BACKFILL_COMPLETED_TOPIC
import com.cowork.project.domain.github.event.GithubBackfillCompletedEvent
import com.cowork.project.domain.github.repository.GithubOrgBackfillStatusRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * org별 GitHub 전체 백필이 끝났다는 신호. 이 레코드가 있는 org만 repo 목록 projection을
 * 신뢰하고, 없으면(아직 백필 전이거나 설치 이전 레거시 org) `GithubAppClient`로 직접 조회한다.
 */
@Component
class GithubBackfillCompletedConsumer(
    private val repository: GithubOrgBackfillStatusRepository,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(
        topics = [GITHUB_BACKFILL_COMPLETED_TOPIC],
        groupId = "cowork-project.github-backfill-completed",
        containerFactory = "githubBackfillCompletedListenerContainerFactory",
    )
    @Transactional
    fun consume(record: ConsumerRecord<String, String>) {
        val event = objectMapper.readValue(record.value(), GithubBackfillCompletedEvent::class.java)
        val status = repository.findById(event.orgLogin).orElseGet {
            GithubOrgBackfillStatus(orgLogin = event.orgLogin, completedAt = event.completedAt)
        }
        if (!event.completedAt.isBefore(status.completedAt)) {
            status.completedAt = event.completedAt
            repository.save(status)
        }
    }
}
