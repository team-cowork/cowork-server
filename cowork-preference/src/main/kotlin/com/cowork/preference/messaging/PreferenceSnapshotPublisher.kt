package com.cowork.preference.messaging

import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.TeamRoleRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class PreferenceSnapshotPublisher(
    private val notificationRepository: NotificationRepository,
    private val preferenceRepository: PreferenceRepository,
    private val teamRoleRepository: TeamRoleRepository,
    private val outboxRepository: PreferenceOutboxRepository,
    private val topicIdentity: ProjectionTopicIdentityProvider,
) {
    private val log = LoggerFactory.getLogger(PreferenceSnapshotPublisher::class.java)

    suspend fun publishAllIfLeader() {
        runCatching {
            outboxRepository.withProjectionSnapshotLock { connection ->
                val notificationCount = publishNotificationSettings(connection)
                val roleCount = publishRoles(connection)
                val assignmentCount = publishAssignments(connection)
                val githubRepoSettingCount = publishGithubRepoSettings(connection)
                publishCompletionMarkers(connection)
                log.info(
                    "Preference projection snapshot published notificationCount={} roleCount={} " +
                        "assignmentCount={} githubRepoSettingCount={}",
                    notificationCount,
                    roleCount,
                    assignmentCount,
                    githubRepoSettingCount,
                )
            }
        }.onFailure {
            log.error("Preference projection snapshot failed; next scheduled run will retry", it)
        }
    }

    private suspend fun publishCompletionMarkers(connection: io.vertx.sqlclient.SqlConnection) {
        val snapshotId = UUID.randomUUID().toString()
        val occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val topicPartitions = SNAPSHOT_TOPICS.associateWith { topic ->
            topicIdentity.topicState(topic).ranges.keys
        }
        val events = ProjectionSnapshotCompletionEvents.create(topicPartitions, snapshotId, occurredAt)
        check(events.isNotEmpty()) { "Projection snapshot topics have no partitions" }
        outboxRepository.inTransaction(connection) { transactionConnection ->
            outboxRepository.enqueueAll(transactionConnection, events)
        }
    }

    private suspend fun publishNotificationSettings(connection: io.vertx.sqlclient.SqlConnection): Int {
        var afterAccountId = -1L
        var afterChannelId = -1L
        var published = 0
        while (true) {
            val page = outboxRepository.inTransaction(connection) { transactionConnection ->
                val lockedPage = notificationRepository.findNotificationPage(
                    transactionConnection,
                    afterAccountId,
                    afterChannelId,
                    PAGE_SIZE,
                )
                outboxRepository.enqueueAll(
                    transactionConnection,
                    lockedPage.map { preference ->
                        PreferenceEvents.channelNotificationChanged(
                            accountId = preference.accountId,
                            channelId = preference.channelId,
                            notification = preference.notification,
                            occurredAt = preference.stateOccurredAt.toInstant(),
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

    private suspend fun publishRoles(connection: io.vertx.sqlclient.SqlConnection): Int {
        var afterRoleId = 0L
        var published = 0
        while (true) {
            val page = outboxRepository.inTransaction(connection) { transactionConnection ->
                val lockedPage = teamRoleRepository.findRolePage(transactionConnection, afterRoleId, PAGE_SIZE)
                outboxRepository.enqueueAll(
                    transactionConnection,
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

    private suspend fun publishAssignments(connection: io.vertx.sqlclient.SqlConnection): Int {
        var afterTeamId = -1L
        var afterAccountId = -1L
        var afterRoleId = -1L
        var published = 0
        while (true) {
            val page = outboxRepository.inTransaction(connection) { transactionConnection ->
                val lockedPage = teamRoleRepository.findAssignmentPage(
                    transactionConnection,
                    afterTeamId,
                    afterAccountId,
                    afterRoleId,
                    PAGE_SIZE,
                )
                outboxRepository.enqueueAll(
                    transactionConnection,
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

    private suspend fun publishGithubRepoSettings(connection: io.vertx.sqlclient.SqlConnection): Int {
        var afterRepoId = 0L
        var published = 0
        while (true) {
            val page = outboxRepository.inTransaction(connection) { transactionConnection ->
                val lockedPage = preferenceRepository.findGithubRepoSettingPage(
                    transactionConnection,
                    afterRepoId,
                    PAGE_SIZE,
                )
                outboxRepository.enqueueAll(
                    transactionConnection,
                    lockedPage.map { state ->
                        PreferenceEvents.githubRepoSettingState(
                            repoId = state.repoId,
                            settings = state.settings,
                            occurredAt = state.stateOccurredAt,
                            snapshot = true,
                        )
                    },
                )
                lockedPage
            }
            if (page.isEmpty()) return published
            published += page.size
            afterRepoId = page.last().repoId
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
        val SNAPSHOT_TOPICS = setOf(
            PreferenceEvents.CHANNEL_NOTIFICATION_TOPIC,
            PreferenceEvents.TEAM_ROLE_TOPIC,
            PreferenceEvents.GITHUB_REPO_SETTING_STATE_TOPIC,
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
