package com.cowork.preference.messaging

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.TeamRoleRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class PreferenceSnapshotPublisher(
    private val notificationRepository: NotificationRepository,
    private val teamRoleRepository: TeamRoleRepository,
    private val outboxRepository: PreferenceOutboxRepository,
    private val topicIdentity: ProjectionTopicIdentityProvider,
    private val cache: PreferenceCache,
) {
    private val log = LoggerFactory.getLogger(PreferenceSnapshotPublisher::class.java)

    suspend fun publishAllIfLeader() {
        val acquired = runCatching { cache.acquireProjectionSnapshotLock() }
            .onFailure { log.error("Preference projection snapshot lock acquisition failed", it) }
            .getOrDefault(false)
        if (!acquired) return

        runCatching {
            val notificationCount = publishNotificationSettings()
            val roleCount = publishRoles()
            val assignmentCount = publishAssignments()
            publishCompletionMarkers()
            log.info(
                "Preference projection snapshot published notificationCount={} roleCount={} assignmentCount={}",
                notificationCount,
                roleCount,
                assignmentCount,
            )
        }.onFailure {
            log.error("Preference projection snapshot failed; next scheduled run will retry", it)
        }
    }

    private suspend fun publishCompletionMarkers() {
        val snapshotId = UUID.randomUUID().toString()
        val occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val topicPartitions = SNAPSHOT_TOPICS.associateWith { topic ->
            topicIdentity.topicState(topic).ranges.keys
        }
        val events = ProjectionSnapshotCompletionEvents.create(topicPartitions, snapshotId, occurredAt)
        check(events.isNotEmpty()) { "Projection snapshot topics have no partitions" }
        outboxRepository.inTransaction { connection ->
            outboxRepository.enqueueAll(connection, events)
        }
    }

    private suspend fun publishNotificationSettings(): Int {
        var afterAccountId = -1L
        var afterChannelId = -1L
        var published = 0
        while (true) {
            val page = outboxRepository.inTransaction { connection ->
                val lockedPage = notificationRepository.findNotificationPage(
                    connection,
                    afterAccountId,
                    afterChannelId,
                    PAGE_SIZE,
                )
                outboxRepository.enqueueAll(
                    connection,
                    lockedPage.map { preference ->
                        PreferenceEvents.channelNotificationChanged(
                            accountId = preference.accountId,
                            channelId = preference.channelId,
                            notification = preference.notification,
                            occurredAt = preference.updatedAt.toInstant(),
                        )
                    },
                )
                lockedPage
            }
            if (page.isEmpty()) return published
            published += page.size
            val last = page.last()
            afterAccountId = last.accountId
            afterChannelId = last.channelId
        }
    }

    private suspend fun publishRoles(): Int {
        var afterRoleId = 0L
        var published = 0
        while (true) {
            val page = outboxRepository.inTransaction { connection ->
                val lockedPage = teamRoleRepository.findRolePage(connection, afterRoleId, PAGE_SIZE)
                outboxRepository.enqueueAll(
                    connection,
                    lockedPage.map { role ->
                        PreferenceEvents.roleUpserted(
                            role,
                            requireNotNull(role.updatedAt ?: role.createdAt) {
                                "Role ${role.id} has no source timestamp"
                            }.toInstant(),
                        )
                    },
                )
                lockedPage
            }
            if (page.isEmpty()) return published
            published += page.size
            afterRoleId = page.last().id
        }
    }

    private suspend fun publishAssignments(): Int {
        var afterTeamId = -1L
        var afterAccountId = -1L
        var afterRoleId = -1L
        var published = 0
        while (true) {
            val page = outboxRepository.inTransaction { connection ->
                val lockedPage = teamRoleRepository.findAssignmentPage(
                    client = connection,
                    afterTeamId = afterTeamId,
                    afterAccountId = afterAccountId,
                    afterRoleId = afterRoleId,
                    limit = PAGE_SIZE,
                )
                outboxRepository.enqueueAll(
                    connection,
                    lockedPage.map { assignment ->
                        PreferenceEvents.assignmentUpserted(
                            assignment,
                            requireNotNull(assignment.updatedAt) {
                                "Assignment ${assignment.teamId}:${assignment.accountId}:${assignment.roleId} has no source timestamp"
                            }.toInstant(),
                        )
                    },
                )
                lockedPage
            }
            if (page.isEmpty()) return published
            published += page.size
            val last = page.last()
            afterTeamId = last.teamId
            afterAccountId = last.accountId
            afterRoleId = last.roleId
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
        val SNAPSHOT_TOPICS = setOf(
            PreferenceEvents.CHANNEL_NOTIFICATION_TOPIC,
            PreferenceEvents.TEAM_ROLE_TOPIC,
        )
    }
}

object ProjectionSnapshotCompletionEvents {
    fun create(
        topicPartitions: Map<String, Set<Int>>,
        snapshotId: String,
        occurredAt: Instant,
    ): List<PreferenceEvent> = topicPartitions.toSortedMap().flatMap { (topic, partitions) ->
        check(partitions.isNotEmpty()) { "Kafka topic has no partitions: $topic" }
        partitions.sorted().map { partition ->
            PreferenceEvents.snapshotCompleted(topic, partition, snapshotId, occurredAt)
        }
    }
}
