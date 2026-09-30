package com.cowork.preference.messaging

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.vertx.sqlclient.SqlConnection
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class PreferenceSnapshotPublisher(
    private val notificationRepository: NotificationRepository,
    private val preferenceRepository: PreferenceRepository,
    private val teamRoleRepository: TeamRoleRepository,
    private val channelRolePolicyRepository: ChannelRolePolicyRepository,
    private val outboxRepository: PreferenceOutboxRepository,
    private val topicIdentity: ProjectionTopicIdentityProvider,
    private val teamStateReadiness: ProjectionReadinessView,
    private val channelRolePolicyReadiness: ProjectionReadinessView,
) {
    private val log = LoggerFactory.getLogger(PreferenceSnapshotPublisher::class.java)

    /** team.member.event만 변경할 수 있는 aggregate이므로 채널 projection readiness를 기다리지 않는다. */
    suspend fun publishTeamStateIfLeader(): Boolean = publishIfLeader(
        aggregate = "team state",
        topics = TEAM_STATE_TOPICS,
        upstreamReadiness = teamStateReadiness,
    ) { connection ->
        val notificationCount = publishNotificationSettings(connection)
        val roleCount = publishRoles(connection)
        val assignmentCount = publishAssignments(connection)
        val memberFenceCount = publishMemberFences(connection)
        val githubRepoSettingCount = publishGithubRepoSettings(connection)
        "notificationCount=$notificationCount roleCount=$roleCount assignmentCount=$assignmentCount " +
            "memberFenceCount=$memberFenceCount githubRepoSettingCount=$githubRepoSettingCount"
    }

    /** 팀 삭제와 채널 삭제가 모두 정책을 정리하므로 두 upstream projection이 따라잡은 뒤에만 완료를 알린다. */
    suspend fun publishChannelRolePoliciesIfLeader(): Boolean = publishIfLeader(
        aggregate = "channel role policy",
        topics = CHANNEL_ROLE_POLICY_TOPICS,
        upstreamReadiness = channelRolePolicyReadiness,
    ) { connection ->
        "channelRolePolicyCount=${publishChannelRolePolicies(connection)}"
    }

    private suspend fun publishIfLeader(
        aggregate: String,
        topics: Set<String>,
        upstreamReadiness: ProjectionReadinessView,
        publishStates: suspend (SqlConnection) -> String,
    ): Boolean {
        if (!upstreamReadiness.isReady) {
            log.info("Deferred {} projection snapshot until upstream projections are ready", aggregate)
            return false
        }
        return runCatching {
            outboxRepository.withProjectionSnapshotLock { connection ->
                val counts = publishStates(connection)
                check(upstreamReadiness.isReady) {
                    "upstream projection became unavailable during $aggregate snapshot"
                }
                publishCompletionMarkers(connection, topics)
                log.info("Published {} projection snapshot {}", aggregate, counts)
            }
        }.onFailure {
            log.error("Failed {} projection snapshot; next scheduled run will retry", aggregate, it)
        }.getOrDefault(false)
    }

    private suspend fun publishCompletionMarkers(connection: SqlConnection, topics: Set<String>) {
        val snapshotId = UUID.randomUUID().toString()
        val occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val topicPartitions = topics.associateWith { topic ->
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
        var afterTeamId = -1L
        var afterRoleId = -1L
        var published = 0
        while (true) {
            val page = outboxRepository.inTransaction(connection) { transactionConnection ->
                val lockedPage = teamRoleRepository.findRoleStatePage(
                    transactionConnection,
                    afterTeamId,
                    afterRoleId,
                    PAGE_SIZE,
                )
                outboxRepository.enqueueAll(
                    transactionConnection,
                    lockedPage.map { state ->
                        state.role?.let { role ->
                            PreferenceEvents.roleUpserted(role, state.stateOccurredAt)
                        } ?: PreferenceEvents.roleDeleted(state.teamId, state.roleId, state.stateOccurredAt)
                    },
                )
                lockedPage
            }
            if (page.isEmpty()) return published
            published += page.size
            val last = page.last()
            afterTeamId = last.teamId
            afterRoleId = last.roleId
        }
    }

    private suspend fun publishAssignments(connection: io.vertx.sqlclient.SqlConnection): Int {
        var afterTeamId = -1L
        var afterAccountId = -1L
        var afterRoleId = -1L
        var published = 0
        while (true) {
            val page = outboxRepository.inTransaction(connection) { transactionConnection ->
                val lockedPage = teamRoleRepository.findAssignmentStatePage(
                    transactionConnection,
                    afterTeamId,
                    afterAccountId,
                    afterRoleId,
                    PAGE_SIZE,
                )
                outboxRepository.enqueueAll(
                    transactionConnection,
                    lockedPage.map { state ->
                        state.assignment?.let { assignment ->
                            PreferenceEvents.assignmentUpserted(assignment, state.stateOccurredAt)
                        } ?: PreferenceEvents.assignmentDeleted(
                            AccountTeamRole(
                                state.accountId,
                                state.teamId,
                                state.roleId,
                            ),
                            state.stateOccurredAt,
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

    private suspend fun publishMemberFences(connection: io.vertx.sqlclient.SqlConnection): Int {
        var afterTeamId = -1L
        var afterAccountId = -1L
        var published = 0
        while (true) {
            val page = outboxRepository.inTransaction(connection) { transactionConnection ->
                val lockedPage = teamRoleRepository.findMemberFencePage(
                    transactionConnection,
                    afterTeamId,
                    afterAccountId,
                    PAGE_SIZE,
                )
                outboxRepository.enqueueAll(
                    transactionConnection,
                    lockedPage.map { fence ->
                        PreferenceEvents.memberAssignmentsDeleted(
                            fence.teamId,
                            fence.accountId,
                            fence.stateOccurredAt,
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

    private suspend fun publishChannelRolePolicies(connection: io.vertx.sqlclient.SqlConnection): Int {
        var afterTeamId = -1L
        var afterChannelId = -1L
        var afterRoleId = -1L
        var published = 0
        while (true) {
            val page = outboxRepository.inTransaction(connection) { transactionConnection ->
                val lockedPage = channelRolePolicyRepository.findPolicyPage(
                    transactionConnection,
                    afterTeamId,
                    afterChannelId,
                    afterRoleId,
                    PAGE_SIZE,
                )
                outboxRepository.enqueueAll(
                    transactionConnection,
                    lockedPage.map { policy ->
                        PreferenceEvents.channelRolePolicyState(
                            teamId = policy.teamId,
                            channelId = policy.channelId,
                            roleId = policy.roleId,
                            permissions = policy.permissions,
                            occurredAt = policy.stateOccurredAt,
                            snapshot = true,
                        )
                    },
                )
                lockedPage
            }
            if (page.isEmpty()) return published
            published += page.size
            val last = page.last()
            afterTeamId = last.teamId
            afterChannelId = last.channelId
            afterRoleId = last.roleId
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
        val TEAM_STATE_TOPICS = setOf(
            PreferenceEvents.CHANNEL_NOTIFICATION_TOPIC,
            PreferenceEvents.TEAM_ROLE_TOPIC,
            PreferenceEvents.GITHUB_REPO_SETTING_STATE_TOPIC,
        )
        val CHANNEL_ROLE_POLICY_TOPICS = setOf(PreferenceEvents.CHANNEL_ROLE_POLICY_STATE_TOPIC)
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
