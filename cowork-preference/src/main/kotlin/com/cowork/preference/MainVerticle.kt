package com.cowork.preference

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.config.AppConfig
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.handler.WebhookHandler
import com.cowork.preference.handler.NotificationHandler
import com.cowork.preference.handler.PreferenceHandler
import com.cowork.preference.handler.ProjectRoleHandler
import com.cowork.preference.messaging.WebhookLegacyProjectionHandler
import com.cowork.preference.messaging.ChannelLifecycleProjectionHandler
import com.cowork.preference.messaging.DmParticipantProjectionHandler
import com.cowork.preference.messaging.ChannelRolePolicyCommandConsumer
import com.cowork.preference.messaging.CombinedProjectionReadiness
import com.cowork.preference.messaging.GithubRepoSettingCommandConsumer
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.messaging.PreferenceOutboxDispatcher
import com.cowork.preference.messaging.PreferenceProducer
import com.cowork.preference.messaging.PreferenceSnapshotPublisher
import com.cowork.preference.messaging.ProjectLifecycleProjectionHandler
import com.cowork.preference.messaging.ProjectionReadiness
import com.cowork.preference.messaging.ProjectionStateConsumer
import com.cowork.preference.messaging.ProjectionTopicIdentityProvider
import com.cowork.preference.messaging.TeamMemberProjectionHandler
import com.cowork.preference.messaging.TeamRoleCommandConsumer
import com.cowork.preference.repository.WebhookRepository
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.DmParticipantProjectionRepository
import com.cowork.preference.repository.ChannelRolePolicyCommandInboxRepository
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.GithubRepoSettingCommandInboxRepository
import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.ProjectAccessStateRepository
import com.cowork.preference.repository.ProjectLifecycleProjectionRepository
import com.cowork.preference.repository.ProjectRoleRepository
import com.cowork.preference.repository.InitialResourceAccessRepository
import com.cowork.preference.repository.ProjectionCheckpointRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleCommandInboxRepository
import com.cowork.preference.repository.TeamRoleRepository
import com.cowork.preference.router.buildRouter
import com.cowork.preference.service.WebhookSecrets
import com.cowork.preference.service.WebhookUrlSigner
import com.cowork.preference.service.WebhookService
import com.cowork.preference.service.ChannelAccessGuard
import com.cowork.preference.service.ChannelLifecycleService
import com.cowork.preference.service.ChannelRolePolicyCommandProcessor
import com.cowork.preference.service.GithubRepoSettingCommandProcessor
import com.cowork.preference.service.NotificationService
import com.cowork.preference.service.PreferenceService
import com.cowork.preference.service.ProjectAccessGuard
import com.cowork.preference.service.ProjectRoleService
import com.cowork.preference.service.InitialResourceAccessService
import com.cowork.preference.service.TeamMembershipGuard
import com.cowork.preference.service.TeamRoleCommandProcessor
import com.cowork.preference.service.TeamRoleService
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.kafka.client.producer.KafkaProducer
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.pgclient.PgBuilder
import io.vertx.pgclient.PgConnectOptions
import io.vertx.redis.client.Redis
import io.vertx.redis.client.RedisOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class MainVerticle : AbstractVerticle() {

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val EUREKA_READINESS_INTERVAL_MS = 1_000L
        private const val INITIAL_PROJECTION_SNAPSHOT_RETRY_INTERVAL_MS = 1_000L
        private const val PROJECTION_SNAPSHOT_INTERVAL_MS = 300_000L
        private const val CACHE_REPAIR_INTERVAL_MS = 1_000L
        private const val TEAM_SNAPSHOT_SOURCE = "cowork-team"
        private const val CHANNEL_SNAPSHOT_SOURCE = "cowork-channel"
        private const val PROJECT_SNAPSHOT_SOURCE = "cowork-project"
    }

    private val log = LoggerFactory.getLogger(MainVerticle::class.java)
    private lateinit var scopeJob: CompletableJob
    private lateinit var scope: CoroutineScope
    private lateinit var preferenceCache: PreferenceCache
    private lateinit var pool: Pool
    private lateinit var redis: Redis
    private lateinit var producer: KafkaProducer<String, String>
    private lateinit var preferenceOutboxDispatcher: PreferenceOutboxDispatcher
    private lateinit var teamMemberProjectionConsumer: ProjectionStateConsumer
    private lateinit var channelLifecycleProjectionConsumer: ProjectionStateConsumer
    private lateinit var projectLifecycleProjectionConsumer: ProjectionStateConsumer
    private lateinit var dmParticipantProjectionConsumer: ProjectionStateConsumer
    private lateinit var webhookLegacyProjectionConsumer: ProjectionStateConsumer
    private lateinit var teamRoleCommandConsumer: TeamRoleCommandConsumer
    private lateinit var githubRepoSettingCommandConsumer: GithubRepoSettingCommandConsumer
    private lateinit var channelRolePolicyCommandConsumer: ChannelRolePolicyCommandConsumer
    private lateinit var projectionTopicIdentity: ProjectionTopicIdentityProvider
    private lateinit var eurekaRegistration: EurekaRegistration
    private var eurekaReadinessTimerId: Long? = null
    private var eurekaHeartbeatTimerId: Long? = null
    private var eurekaRegistered = false

    override fun start(startPromise: Promise<Void>) {
        scopeJob = SupervisorJob()
        scope = CoroutineScope(scopeJob)

        val appConfig = AppConfig.from(config())

        pool = buildPgPool(appConfig)
        redis = buildRedis(appConfig)
        preferenceCache = PreferenceCache(redis, MetricsRegistry.registry)

        producer = buildKafkaProducer(appConfig)
        val preferenceProducer = PreferenceProducer(producer)

        val prefRepo = PreferenceRepository(pool)
        val webhookRepository = WebhookRepository()
        val notifRepo = NotificationRepository(pool)
        val roleRepo = ProjectRoleRepository(pool)
        val teamRoleRepo = TeamRoleRepository(pool)
        val channelRolePolicyRepo = ChannelRolePolicyRepository(pool)
        val teamMemberProjectionRepo = TeamMemberProjectionRepository()
        val channelLifecycleProjectionRepo = ChannelLifecycleProjectionRepository()
        val projectLifecycleProjectionRepo = ProjectLifecycleProjectionRepository()
        val dmParticipantProjectionRepo = DmParticipantProjectionRepository()
        val checkpointRepository = ProjectionCheckpointRepository(pool)
        val commandInboxRepository = TeamRoleCommandInboxRepository(pool)
        val githubRepoSettingCommandInboxRepository = GithubRepoSettingCommandInboxRepository(pool)
        val channelRolePolicyCommandInboxRepository = ChannelRolePolicyCommandInboxRepository(pool)
        val outboxRepository = PreferenceOutboxRepository(pool)
        val projectionReadiness = ProjectionReadiness()
        val channelProjectionReadiness = ProjectionReadiness()
        val projectLifecycleReadiness = ProjectionReadiness()
        val dmParticipantReadiness = ProjectionReadiness()
        val webhookLegacyReadiness = ProjectionReadiness()
        val webhookReadiness = CombinedProjectionReadiness(listOf(webhookLegacyReadiness, channelProjectionReadiness))
        // 채널 역할 정책은 팀 삭제와 채널 삭제가 모두 정리하므로 정책 command와 snapshot만 두 projection을 기다린다.
        // 채널 설정 수정도 채널 소속 팀과 팀 역할을 함께 읽으므로 같은 조합을 쓴다.
        val channelRolePolicyReadiness = CombinedProjectionReadiness(
            listOf(projectionReadiness, channelProjectionReadiness),
        )
        // HTTP API는 팀·프로젝트·채널 projection을 읽지만 준비되기 전에는 해당 API만 503으로 응답한다.
        // 소유 서비스 장애가 전체 트래픽 차단으로 번지지 않도록 /health/ready와 Eureka는 팀 projection만 기다린다.
        val projectAccessReadiness = CombinedProjectionReadiness(listOf(projectionReadiness, projectLifecycleReadiness))
        val channelAccessReadiness = CombinedProjectionReadiness(
            listOf(projectionReadiness, channelProjectionReadiness, dmParticipantReadiness),
        )
        projectionTopicIdentity = ProjectionTopicIdentityProvider(appConfig.kafka.bootstrapServers)

        val prefService = PreferenceService(prefRepo, preferenceCache, outboxRepository)
        val notifService = NotificationService(notifRepo, outboxRepository)
        val projectAccessRepo = ProjectAccessStateRepository(outboxRepository)
        val initialAccessRepo = InitialResourceAccessRepository()
        val initialAccessService = InitialResourceAccessService(
            initialAccessRepo, teamMemberProjectionRepo, teamRoleRepo, channelRolePolicyRepo,
            roleRepo, outboxRepository, projectionReadiness,
        )
        val teamRoleService = TeamRoleService(teamRoleRepo, outboxRepository, channelRolePolicyRepo, projectAccessRepo)
        val channelLifecycleService = ChannelLifecycleService(
            projectionRepository = channelLifecycleProjectionRepo,
            policyRepository = channelRolePolicyRepo,
            outboxRepository = outboxRepository,
            initialAccess = initialAccessService,
            webhookRepository = webhookRepository,
        )
        val teamRoleCommandProcessor = TeamRoleCommandProcessor(
            roleRepository = teamRoleRepo,
            memberRepository = teamMemberProjectionRepo,
            inboxRepository = commandInboxRepository,
            outboxRepository = outboxRepository,
            readiness = projectionReadiness,
            channelRolePolicyRepository = channelRolePolicyRepo,
            projectAccess = projectAccessRepo,
        )
        val githubRepoSettingCommandProcessor = GithubRepoSettingCommandProcessor(
            preferenceRepository = prefRepo,
            inboxRepository = githubRepoSettingCommandInboxRepository,
            outboxRepository = outboxRepository,
            cache = preferenceCache,
        )
        val channelRolePolicyCommandProcessor = ChannelRolePolicyCommandProcessor(
            policyRepository = channelRolePolicyRepo,
            roleRepository = teamRoleRepo,
            memberRepository = teamMemberProjectionRepo,
            channelRepository = channelLifecycleProjectionRepo,
            inboxRepository = channelRolePolicyCommandInboxRepository,
            outboxRepository = outboxRepository,
            readiness = channelRolePolicyReadiness,
            initialAccess = initialAccessRepo,
        )

        val teamMembershipGuard = TeamMembershipGuard(pool, teamMemberProjectionRepo, projectionReadiness, teamRoleRepo)
        val projectAccessGuard = ProjectAccessGuard(
            pool,
            projectLifecycleProjectionRepo,
            teamMemberProjectionRepo,
            projectAccessReadiness,
            roleRepo,
            teamRoleRepo,
        )
        val roleService = ProjectRoleService(roleRepo, outboxRepository, projectAccessRepo, initialAccessRepo, projectAccessGuard)
        val channelAccessGuard = ChannelAccessGuard(
            pool = pool,
            channelRepository = channelLifecycleProjectionRepo,
            dmParticipantRepository = dmParticipantProjectionRepo,
            teamMemberRepository = teamMemberProjectionRepo,
            policyRepository = channelRolePolicyRepo,
            accessReadiness = channelAccessReadiness,
            managerReadiness = channelRolePolicyReadiness,
        )
        val prefHandler = PreferenceHandler(
            prefService,
            scope,
            teamMembershipGuard,
            projectAccessGuard,
            channelAccessGuard,
        )
        val webhookService = WebhookService(pool, webhookRepository, channelLifecycleProjectionRepo, channelAccessGuard,
            outboxRepository, webhookReadiness, WebhookSecrets(appConfig.webhookSecretEncryptionKey),
            WebhookUrlSigner(appConfig.webhookUrlSigningKey))
        val webhookHandler = WebhookHandler(webhookService, scope)
        val notifHandler = NotificationHandler(notifService, scope, channelAccessGuard)
        val roleHandler = ProjectRoleHandler(roleService, scope, projectAccessGuard)

        val router = buildRouter(
            vertx,
            prefHandler,
            notifHandler,
            roleHandler,
            projectionReadiness,
        )
        webhookHandler.routes(router)

        scheduleExpiryChecks(prefRepo, outboxRepository, preferenceCache, webhookService)
        scheduleCacheRepair(preferenceCache)
        val snapshotPublisher = PreferenceSnapshotPublisher(
            projectAccessRepository = projectAccessRepo,
            projectAccessReadiness = projectAccessReadiness,
            notificationRepository = notifRepo,
            preferenceRepository = prefRepo,
            teamRoleRepository = teamRoleRepo,
            teamRoleCommandInboxRepository = commandInboxRepository,
            channelRolePolicyRepository = channelRolePolicyRepo,
            outboxRepository = outboxRepository,
            topicIdentity = projectionTopicIdentity,
            teamStateReadiness = projectionReadiness,
            channelRolePolicyReadiness = channelRolePolicyReadiness,
            webhookRepository = webhookRepository,
            webhookReadiness = webhookReadiness,
        )
        scheduleProjectionSnapshots(snapshotPublisher::publishTeamStateIfLeader)
        scheduleProjectionSnapshots(snapshotPublisher::publishChannelRolePoliciesIfLeader)
        scheduleProjectionSnapshots(snapshotPublisher::publishProjectAccessIfLeader)
        scheduleProjectionSnapshots(snapshotPublisher::publishSettingStatesIfLeader)
        scheduleProjectionSnapshots(snapshotPublisher::publishWebhooksIfLeader)

        preferenceOutboxDispatcher = PreferenceOutboxDispatcher(
            vertx = vertx,
            repository = outboxRepository,
            producer = preferenceProducer,
            scope = scope,
        )
        preferenceOutboxDispatcher.start()

        teamMemberProjectionConsumer = ProjectionStateConsumer(
            vertx = vertx,
            bootstrapServers = appConfig.kafka.bootstrapServers,
            groupId = appConfig.kafka.teamMemberConsumerGroupId,
            topic = appConfig.kafka.teamMemberTopic,
            expectedSnapshotSource = TEAM_SNAPSHOT_SOURCE,
            handler = TeamMemberProjectionHandler(teamRoleService, teamMemberProjectionRepo, checkpointRepository, projectAccessRepo),
            checkpointRepository = checkpointRepository,
            topicIdentity = projectionTopicIdentity,
            readiness = projectionReadiness,
            scope = scope,
        )
        teamMemberProjectionConsumer.start()

        channelLifecycleProjectionConsumer = ProjectionStateConsumer(
            vertx = vertx,
            bootstrapServers = appConfig.kafka.bootstrapServers,
            groupId = appConfig.kafka.channelLifecycleConsumerGroupId,
            topic = appConfig.kafka.channelTopic,
            expectedSnapshotSource = CHANNEL_SNAPSHOT_SOURCE,
            handler = ChannelLifecycleProjectionHandler(channelLifecycleService, checkpointRepository),
            checkpointRepository = checkpointRepository,
            topicIdentity = projectionTopicIdentity,
            readiness = channelProjectionReadiness,
            scope = scope,
        )
        channelLifecycleProjectionConsumer.start()

        webhookLegacyProjectionConsumer = ProjectionStateConsumer(
            vertx = vertx,
            bootstrapServers = appConfig.kafka.bootstrapServers,
            groupId = appConfig.kafka.webhookLegacyConsumerGroupId,
            topic = PreferenceEvents.WEBHOOK_LEGACY_STATE_TOPIC,
            expectedSnapshotSource = CHANNEL_SNAPSHOT_SOURCE,
            handler = WebhookLegacyProjectionHandler(webhookRepository, channelLifecycleProjectionRepo,
                checkpointRepository, outboxRepository, channelProjectionReadiness),
            checkpointRepository = checkpointRepository,
            topicIdentity = projectionTopicIdentity,
            readiness = webhookLegacyReadiness,
            scope = scope,
        )
        webhookLegacyProjectionConsumer.start()

        projectLifecycleProjectionConsumer = ProjectionStateConsumer(
            vertx = vertx,
            bootstrapServers = appConfig.kafka.bootstrapServers,
            groupId = appConfig.kafka.projectLifecycleConsumerGroupId,
            topic = appConfig.kafka.projectTopic,
            expectedSnapshotSource = PROJECT_SNAPSHOT_SOURCE,
            handler = ProjectLifecycleProjectionHandler(
                projectLifecycleProjectionRepo, checkpointRepository, projectAccessRepo, initialAccessService,
            ),
            checkpointRepository = checkpointRepository,
            topicIdentity = projectionTopicIdentity,
            readiness = projectLifecycleReadiness,
            scope = scope,
        )
        projectLifecycleProjectionConsumer.start()

        dmParticipantProjectionConsumer = ProjectionStateConsumer(
            vertx = vertx,
            bootstrapServers = appConfig.kafka.bootstrapServers,
            groupId = appConfig.kafka.dmParticipantConsumerGroupId,
            topic = appConfig.kafka.dmParticipantTopic,
            expectedSnapshotSource = CHANNEL_SNAPSHOT_SOURCE,
            handler = DmParticipantProjectionHandler(dmParticipantProjectionRepo, checkpointRepository),
            checkpointRepository = checkpointRepository,
            topicIdentity = projectionTopicIdentity,
            readiness = dmParticipantReadiness,
            scope = scope,
        )
        dmParticipantProjectionConsumer.start()

        teamRoleCommandConsumer = TeamRoleCommandConsumer(
            vertx = vertx,
            bootstrapServers = appConfig.kafka.bootstrapServers,
            groupId = appConfig.kafka.teamRoleCommandConsumerGroupId,
            processor = teamRoleCommandProcessor,
            inboxRepository = commandInboxRepository,
            scope = scope,
        )
        teamRoleCommandConsumer.start()

        githubRepoSettingCommandConsumer = GithubRepoSettingCommandConsumer(
            vertx = vertx,
            bootstrapServers = appConfig.kafka.bootstrapServers,
            groupId = appConfig.kafka.githubRepoSettingCommandConsumerGroupId,
            processor = githubRepoSettingCommandProcessor,
            inboxRepository = githubRepoSettingCommandInboxRepository,
            scope = scope,
        )
        githubRepoSettingCommandConsumer.start()

        channelRolePolicyCommandConsumer = ChannelRolePolicyCommandConsumer(
            vertx = vertx,
            bootstrapServers = appConfig.kafka.bootstrapServers,
            groupId = appConfig.kafka.channelRolePolicyCommandConsumerGroupId,
            processor = channelRolePolicyCommandProcessor,
            inboxRepository = channelRolePolicyCommandInboxRepository,
            scope = scope,
        )
        channelRolePolicyCommandConsumer.start()

        vertx.createHttpServer()
            .requestHandler(router)
            .listen(appConfig.serverPort).onComplete { result ->
                if (result.succeeded()) {
                    log.info("cowork-preference listening on port {}", appConfig.serverPort)
                    eurekaRegistration = EurekaRegistration(appConfig)
                    syncEurekaRegistration(projectionReadiness)
                    eurekaReadinessTimerId = vertx.setPeriodic(EUREKA_READINESS_INTERVAL_MS) {
                        syncEurekaRegistration(projectionReadiness)
                    }
                    eurekaHeartbeatTimerId = vertx.setPeriodic(HEARTBEAT_INTERVAL_MS) {
                        registerHeartbeat(projectionReadiness)
                    }
                    startPromise.complete()
                } else {
                    startPromise.fail(result.cause())
                }
            }
    }

    private fun syncEurekaRegistration(readiness: ProjectionReadiness) {
        if (readiness.isReady && !eurekaRegistered) {
            runCatching { eurekaRegistration.register() }
                .onSuccess { eurekaRegistered = true }
                .onFailure { log.warn("eureka registration failed", it) }
            return
        }
        if (!readiness.isReady && eurekaRegistered) {
            runCatching { eurekaRegistration.deregister() }
                .onFailure { log.warn("eureka deregistration while unready failed", it) }
            eurekaRegistered = false
        }
    }

    private fun registerHeartbeat(readiness: ProjectionReadiness) {
        if (!readiness.isReady || !eurekaRegistered) return
        runCatching { eurekaRegistration.heartbeat() }
            .onFailure { log.warn("eureka heartbeat failed", it) }
    }

    private fun scheduleExpiryChecks(
        prefRepo: PreferenceRepository,
        outboxRepository: PreferenceOutboxRepository,
        cache: PreferenceCache,
        webhookService: WebhookService,
    ) {
        vertx.setPeriodic(60_000L) {
            scope.launch(vertx.dispatcher()) {
                runCatching { webhookService.clearExpiredReplaySecrets() }
                    .onFailure { log.error("Failed to clear expired webhook response secrets", it) }
                checkExpiredStatuses(prefRepo, outboxRepository, cache)
            }
        }
    }

    private fun scheduleCacheRepair(cache: PreferenceCache) {
        val repairInProgress = AtomicBoolean(false)
        vertx.setPeriodic(CACHE_REPAIR_INTERVAL_MS) {
            if (!repairInProgress.compareAndSet(false, true)) return@setPeriodic
            scope.launch(vertx.dispatcher()) {
                try {
                    cache.repairPending()
                } finally {
                    repairInProgress.set(false)
                }
            }
        }
    }

    private fun scheduleProjectionSnapshots(publishIfLeader: suspend () -> Boolean) {
        val startupAttemptInProgress = AtomicBoolean(false)
        vertx.setPeriodic(INITIAL_PROJECTION_SNAPSHOT_RETRY_INTERVAL_MS) { timerId ->
            if (!startupAttemptInProgress.compareAndSet(false, true)) return@setPeriodic
            scope.launch(vertx.dispatcher()) {
                try {
                    if (publishIfLeader()) vertx.cancelTimer(timerId)
                } finally {
                    startupAttemptInProgress.set(false)
                }
            }
        }
        vertx.setPeriodic(PROJECTION_SNAPSHOT_INTERVAL_MS) {
            scope.launch(vertx.dispatcher()) {
                publishIfLeader()
            }
        }
    }

    private suspend fun checkExpiredStatuses(
        prefRepo: PreferenceRepository,
        outboxRepository: PreferenceOutboxRepository,
        cache: PreferenceCache,
    ) {
        if (!cache.acquireExpiryLock()) return
        runCatching {
            val expired = outboxRepository.inTransaction { connection ->
                val lockedExpired = prefRepo.findExpiredAccountStatuses(connection)
                if (lockedExpired.isEmpty()) return@inTransaction emptyList()
                val clearedStates = prefRepo.clearExpiredStatuses(connection, lockedExpired.map { it.first })
                val occurredAt = Instant.now()
                outboxRepository.enqueueAll(
                    connection,
                    clearedStates.map { state ->
                        PreferenceEvents.accountSettingState(
                            accountId = state.resourceId,
                            settings = state.settings,
                            occurredAt = state.stateOccurredAt,
                            snapshot = false,
                        )
                    },
                )
                outboxRepository.enqueueAll(
                    connection,
                    lockedExpired.map { (accountId, previousStatus) ->
                        PreferenceEvents.statusChanged(
                            accountId = accountId,
                            previousStatus = previousStatus,
                            newStatus = null,
                            reason = "EXPIRED",
                            occurredAt = occurredAt,
                        )
                    },
                )
                lockedExpired
            }
            if (expired.isEmpty()) return
            expired.forEach { (accountId, _) ->
                cache.invalidateSettings(ResourceType.ACCOUNT, accountId)
                log.info("Status expired for accountId={}", accountId)
            }
        }.onFailure { log.error("Error checking expired statuses", it) }
    }

    private fun buildPgPool(config: AppConfig): Pool {
        val connectOptions = PgConnectOptions()
            .setHost(config.db.host)
            .setPort(config.db.port)
            .setDatabase(config.db.database)
            .setUser(config.db.username)
            .setPassword(config.db.password)
            .addProperty("search_path", config.db.schema)
        val poolOptions = PoolOptions().setMaxSize(config.db.poolSize)
        return PgBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build()
    }

    private fun buildRedis(config: AppConfig): Redis {
        val options = RedisOptions()
            .setConnectionString("redis://${config.redis.host}:${config.redis.port}")
        return Redis.createClient(vertx, options)
    }

    private fun buildKafkaProducer(config: AppConfig): KafkaProducer<String, String> {
        val kafkaConfig = mapOf(
            "bootstrap.servers" to config.kafka.bootstrapServers,
            "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "acks" to "all",
            "enable.idempotence" to "true",
            "max.in.flight.requests.per.connection" to "5",
            "delivery.timeout.ms" to "10000",
            "request.timeout.ms" to "5000",
        )
        return KafkaProducer.create(vertx, kafkaConfig)
    }

    override fun stop(stopPromise: Promise<Void>) {
        eurekaReadinessTimerId?.let { vertx.cancelTimer(it) }
        eurekaHeartbeatTimerId?.let { vertx.cancelTimer(it) }
        if (::preferenceOutboxDispatcher.isInitialized) preferenceOutboxDispatcher.close()
        if (::eurekaRegistration.isInitialized && eurekaRegistered) {
            runCatching { eurekaRegistration.deregister() }
                .onFailure { log.warn("eureka deregister failed", it) }
        }

        val quiesceFutures = mutableListOf<Future<Void>>()
        if (::githubRepoSettingCommandConsumer.isInitialized) {
            quiesceFutures.add(githubRepoSettingCommandConsumer.close())
        }
        if (::channelRolePolicyCommandConsumer.isInitialized) {
            quiesceFutures.add(channelRolePolicyCommandConsumer.close())
        }
        if (::teamRoleCommandConsumer.isInitialized) quiesceFutures.add(teamRoleCommandConsumer.close())
        if (::webhookLegacyProjectionConsumer.isInitialized) quiesceFutures.add(webhookLegacyProjectionConsumer.close())
        if (::teamMemberProjectionConsumer.isInitialized) quiesceFutures.add(teamMemberProjectionConsumer.close())
        if (::channelLifecycleProjectionConsumer.isInitialized) {
            quiesceFutures.add(channelLifecycleProjectionConsumer.close())
        }
        if (::projectLifecycleProjectionConsumer.isInitialized) {
            quiesceFutures.add(projectLifecycleProjectionConsumer.close())
        }
        if (::dmParticipantProjectionConsumer.isInitialized) {
            quiesceFutures.add(dmParticipantProjectionConsumer.close())
        }
        if (::scopeJob.isInitialized) quiesceFutures.add(cancelScopeAndAwaitChildren())

        allCompleted(quiesceFutures).compose {
            if (::projectionTopicIdentity.isInitialized) projectionTopicIdentity.close()
            if (::redis.isInitialized) redis.close()
            val resourceFutures = mutableListOf<Future<Void>>()
            if (::pool.isInitialized) resourceFutures.add(pool.close())
            if (::producer.isInitialized) resourceFutures.add(producer.close())
            allCompleted(resourceFutures)
        }.onComplete { ar ->
            if (ar.succeeded()) stopPromise.complete() else stopPromise.fail(ar.cause())
        }
    }

    private fun cancelScopeAndAwaitChildren(): Future<Void> {
        val promise = Promise.promise<Void>()
        scopeJob.invokeOnCompletion { error ->
            if (error == null || error is CancellationException) {
                promise.complete()
            } else {
                promise.fail(error)
            }
        }
        scopeJob.cancel()
        return promise.future()
    }

    private fun allCompleted(futures: List<Future<Void>>): Future<Void> =
        if (futures.isEmpty()) Future.succeededFuture() else Future.all(futures).mapEmpty()
}
