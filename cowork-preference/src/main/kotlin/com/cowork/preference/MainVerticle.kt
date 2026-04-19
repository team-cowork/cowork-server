package com.cowork.preference

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.config.AppConfig
import com.cowork.preference.handler.NotificationHandler
import com.cowork.preference.handler.PreferenceHandler
import com.cowork.preference.handler.ProjectRoleHandler
import com.cowork.preference.messaging.PreferenceProducer
import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.ProjectRoleRepository
import com.cowork.preference.router.buildRouter
import com.cowork.preference.service.NotificationService
import com.cowork.preference.service.PreferenceService
import com.cowork.preference.service.ProjectRoleService
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject
import io.vertx.kafka.client.producer.KafkaProducer
import io.vertx.pgclient.PgBuilder
import io.vertx.pgclient.PgConnectOptions
import io.vertx.redis.client.Redis
import io.vertx.sqlclient.Pool
import io.vertx.redis.client.RedisAPI
import io.vertx.redis.client.RedisOptions
import io.vertx.sqlclient.PoolOptions
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class MainVerticle : AbstractVerticle() {

    private val log = LoggerFactory.getLogger(MainVerticle::class.java)
    private lateinit var scope: CoroutineScope
    private lateinit var preferenceCache: PreferenceCache

    override fun start(startPromise: Promise<Void>) {
        scope = CoroutineScope(SupervisorJob())

        val configJson = config()
        val appConfig = AppConfig.from(configJson)

        val pool = buildPgPool(appConfig)
        val redis = buildRedis(appConfig)
        val redisApi = RedisAPI.api(redis)
        preferenceCache = PreferenceCache(redisApi)

        val producer = buildKafkaProducer(appConfig)
        val preferenceProducer = PreferenceProducer(producer)

        val prefRepo = PreferenceRepository(pool)
        val notifRepo = NotificationRepository(pool)
        val roleRepo = ProjectRoleRepository(pool)

        val prefService = PreferenceService(prefRepo, preferenceCache, preferenceProducer)
        val notifService = NotificationService(notifRepo, preferenceCache)
        val roleService = ProjectRoleService(roleRepo)

        val prefHandler = PreferenceHandler(prefService, scope)
        val notifHandler = NotificationHandler(notifService, scope)
        val roleHandler = ProjectRoleHandler(roleService, scope)

        val router = buildRouter(vertx, prefHandler, notifHandler, roleHandler)

        scheduleStatusExpiryCheck(prefRepo, preferenceProducer)

        vertx.createHttpServer()
            .requestHandler(router)
            .listen(appConfig.serverPort) { result ->
                if (result.succeeded()) {
                    log.info("cowork-preference listening on port {}", appConfig.serverPort)
                    startPromise.complete()
                } else {
                    startPromise.fail(result.cause())
                }
            }
    }

    private fun scheduleStatusExpiryCheck(
        prefRepo: PreferenceRepository,
        producer: PreferenceProducer,
    ) {
        vertx.setPeriodic(60_000L) {
            scope.launch(vertx.dispatcher()) {
                checkExpiredStatuses(prefRepo, producer)
            }
        }
    }

    private suspend fun checkExpiredStatuses(
        prefRepo: PreferenceRepository,
        producer: PreferenceProducer,
    ) {
        runCatching {
            val expired = prefRepo.findExpiredAccountStatuses()
            expired.forEach { (accountId, previousStatus) ->
                producer.publishStatusChanged(
                    accountId = accountId,
                    previousStatus = previousStatus,
                    newStatus = null,
                    reason = "EXPIRED",
                )
                prefRepo.clearExpiredStatus(accountId)
                preferenceCache.invalidateSettings(
                    com.cowork.preference.domain.ResourceType.ACCOUNT,
                    accountId,
                )
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
            "acks" to "1",
        )
        return KafkaProducer.create(vertx, kafkaConfig)
    }

    override fun stop() {
        scope.cancel()
    }
}
