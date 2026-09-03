package com.cowork.preference

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.config.AppConfig
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.handler.NotificationHandler
import com.cowork.preference.handler.PreferenceHandler
import com.cowork.preference.handler.ProjectRoleHandler
import com.cowork.preference.messaging.ChannelRolePolicyCommandConsumer
import com.cowork.preference.messaging.GithubRepoSettingCommandConsumer
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.messaging.PreferenceOutboxDispatcher
import com.cowork.preference.messaging.PreferenceProducer
import com.cowork.preference.messaging.PreferenceSnapshotPublisher
import com.cowork.preference.messaging.ProjectionReadiness
import com.cowork.preference.messaging.ProjectionTopicIdentityProvider
import com.cowork.preference.messaging.TeamMemberProjectionConsumer
import com.cowork.preference.messaging.TeamRoleCommandConsumer
import com.cowork.preference.repository.ChannelRolePolicyCommandInboxRepository
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.GithubRepoSettingCommandInboxRepository
import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.ProjectRoleRepository
import com.cowork.preference.repository.ProjectionCheckpointRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleCommandInboxRepository
import com.cowork.preference.repository.TeamRoleRepository
import com.cowork.preference.router.buildRouter
import com.cowork.preference.service.ChannelRolePolicyCommandProcessor
import com.cowork.preference.service.GithubRepoSettingCommandProcessor
import com.cowork.preference.service.NotificationService
import com.cowork.preference.service.PreferenceService
import com.cowork.preference.service.ProjectRoleService
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
import io.vertx.redis.client.RedisAPI
import io.vertx.redis.client.RedisOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class MainVerticle : AbstractVerticle() {

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val EUREKA_READINESS_INTERVAL_MS = 1_000L
        private const val INITIAL_PROJECTION_SNAPSHOT_RETRY_INTERVAL_MS = 1_000L
        private const val PROJECTION_SNAPSHOT_INTERVAL_MS = 300_000L
    }

    private val log = LoggerFactory.getLogger(MainVerticle::class.java)
    private lateinit var scopeJob: CompletableJob
    private lateinit var scope: CoroutineScope
    private lateinit var preferenceCache: PreferenceCache
    private lateinit var pool: Pool
    private lateinit var redis: Redis
    private lateinit var producer: KafkaProducer<String, String>
    private lateinit var preferenceOutboxDispatcher: PreferenceOutboxDispatcher
    private lateinit var teamMemberProjectionConsumer: TeamMemberProjectionConsumer
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
        val redisApi = RedisAPI.api(redis)
        preferenceCache = PreferenceCache(redisApi)

        producer = buildKafkaProducer(appConfig)
        val preferenceProducer = PreferenceProducer(producer)

        val prefRepo = PreferenceRepository(pool)
        val notifRepo = NotificationRepository(pool)
        val roleRepo = ProjectRoleRepository(pool)
        val teamRoleRepo = TeamRoleRepository(pool)
        val channelRolePolicyRepo = ChannelRolePolicyRepository(pool)
        val teamMemberProjectionRepo = TeamMemberProjectionRepository()
        val checkpointRepository = ProjectionCheckpointRepository(pool)
        val commandInboxRepository = TeamRoleCommandInboxRepository(pool)
        val githubRepoSettingCommandInboxRepository = GithubRepoSettingCommandInboxRepository(pool)
        val channelRolePolicyCommandInboxRepository = ChannelRolePolicyCommandInboxRepository(pool)
        val outboxRepository = PreferenceOutboxRepository(pool)
        val projectionReadiness = ProjectionReadiness()
        projectionTopicIdentity = ProjectionTopicIdentityProvider(appConfig.kafka.bootstrapServers)

        val prefService = PreferenceService(prefRepo, preferenceCache, outboxRepository)
        val notifService = NotificationService(notifRepo, outboxRepository)
        val roleService = ProjectRoleService(roleRepo)
        val teamRoleService = TeamRoleService(teamRoleRepo, outboxRepository, channelRolePolicyRepo)
        val teamRoleCommandProcessor = TeamRoleCommandProcessor(
            roleRepository = teamRoleRepo,
            memberRepository = teamMemberProjectionRepo,
            inboxRepository = commandInboxRepository,
            outboxRepository = outboxRepository,
            readiness = projectionReadiness,
            channelRolePolicyRepository = channelRolePolicyRepo,
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
            inboxRepository = channelRolePolicyCommandInboxRepository,
            outboxRepository = outboxRepository,
            readiness = projectionReadiness,
        )

        val prefHandler = PreferenceHandler(prefService, scope)
        val notifHandler = NotificationHandler(notifService, scope)
        val roleHandler = ProjectRoleHandler(roleService, scope)

        val router = buildRouter(
            vertx,
            prefHandler,
            notifHandler,
            roleHandler,
            projectionReadiness,
        )

        scheduleStatusExpiryCheck(prefRepo, outboxRepository, preferenceCache)
        val snapshotPublisher = PreferenceSnapshotPublisher(
            notificationRepository = notifRepo,
            preferenceRepository = prefRepo,
            teamRoleRepository = teamRoleRepo,
            channelRolePolicyRepository = channelRolePolicyRepo,
            outboxRepository = outboxRepository,
            topicIdentity = projectionTopicIdentity,
            upstreamReadiness = projectionReadiness,
        )
        scheduleProjectionSnapshots(snapshotPublisher)

        preferenceOutboxDispatcher = PreferenceOutboxDispatcher(
            vertx = vertx,
            repository = outboxRepository,
            producer = preferenceProducer,
            scope = scope,
        )
        preferenceOutboxDispatcher.start()

        teamMemberProjectionConsumer = TeamMemberProjectionConsumer(
            vertx = vertx,
            bootstrapServers = appConfig.kafka.bootstrapServers,
            groupId = appConfig.kafka.teamMemberConsumerGroupId,
            topic = appConfig.kafka.teamMemberTopic,
            teamRoleService = teamRoleService,
            teamMemberProjectionRepository = teamMemberProjectionRepo,
            checkpointRepository = checkpointRepository,
            topicIdentity = projectionTopicIdentity,
            readiness = projectionReadiness,
            scope = scope,
        )
        teamMemberProjectionConsumer.start()

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

    private fun scheduleStatusExpiryCheck(
        prefRepo: PreferenceRepository,
        outboxRepository: PreferenceOutboxRepository,
        cache: PreferenceCache,
    ) {
        vertx.setPeriodic(60_000L) {
            scope.launch(vertx.dispatcher()) {
                checkExpiredStatuses(prefRepo, outboxRepository, cache)
            }
        }
    }

    private fun scheduleProjectionSnapshots(snapshotPublisher: PreferenceSnapshotPublisher) {
        val startupAttemptInProgress = AtomicBoolean(false)
        vertx.setPeriodic(INITIAL_PROJECTION_SNAPSHOT_RETRY_INTERVAL_MS) { timerId ->
            if (!startupAttemptInProgress.compareAndSet(false, true)) return@setPeriodic
            scope.launch(vertx.dispatcher()) {
                try {
                    if (snapshotPublisher.publishAllIfLeader()) vertx.cancelTimer(timerId)
                } finally {
                    startupAttemptInProgress.set(false)
                }
            }
        }
        vertx.setPeriodic(PROJECTION_SNAPSHOT_INTERVAL_MS) {
            scope.launch(vertx.dispatcher()) {
                snapshotPublisher.publishAllIfLeader()
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
                prefRepo.clearExpiredStatuses(connection, lockedExpired.map { it.first })
                val occurredAt = Instant.now()
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
        if (::teamMemberProjectionConsumer.isInitialized) quiesceFutures.add(teamMemberProjectionConsumer.close())
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
