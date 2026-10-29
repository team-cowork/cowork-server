import {
	Controller as controller,
	Get as httpGet,
	HttpException,
	HttpStatus,
	Inject as inject,
} from '@nestjs/common';
import {InjectConnection as injectConnection} from '@nestjs/mongoose';
import mongoose from 'mongoose';
import {publicRoute} from './common/guard/public.decorator.js';
import {RedisRateLimiter} from './common/util/redis-rate-limiter.js';
import {SocketIoRedisConnection} from './common/adapter/redis-io.adapter.js';
import {ChatMessageProducer} from './chat/kafka/chat-message.producer.js';
import {ProjectionReadinessService} from './common/kafka/projection-readiness.service.js';
import {ElasticsearchService} from './search/elasticsearch.service.js';

@publicRoute()
@controller('health')
export class HealthController {
	@injectConnection()
	private readonly mongoConnection!: mongoose.Connection;

	@inject()
	private readonly redisRateLimiter!: RedisRateLimiter;

	@inject()
	private readonly chatMessageProducer!: ChatMessageProducer;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	@inject()
	private readonly elasticsearchService!: ElasticsearchService;

	@inject()
	private readonly socketIoRedis!: SocketIoRedisConnection;

	@httpGet()
	health() {
		return {status: 'UP'};
	}

	/**
     MongoDB/Redis/Kafka 의존성 상태를 점검하는 readiness 체크.
     하나라도 비정상이면 503을 반환해 오케스트레이터가 해당 인스턴스로 트래픽을 보내지 않도록 한다.
     `redis`는 rate limiter client의 PING이고, `socketIoAdapter`는 replica 간 브로드캐스트를 맡는
     Socket.IO Redis adapter의 pub/sub 준비 상태다. 서로 다른 연결이므로 따로 판정한다.

     검색 색인 준비 여부(`searchIndex`)는 readiness를 막지 않는다. Elasticsearch가 중단돼도
     메시지 송수신은 계속되어야 하고, 색인 의도는 아웃박스에 durable하게 쌓여 복구 후 수렴한다.
     대신 검색 API가 색인 미준비 상태에서 빈 결과 대신 503을 반환하고, 이 값은 지표·점검용으로만 노출한다.
     */
	@httpGet('ready')
	async ready() {
		const dependencies = {
			mongo: this.mongoConnection.readyState === mongoose.ConnectionStates.connected,
			redis: await this.redisRateLimiter.ping(),
			kafka: this.chatMessageProducer.isReady(),
			projections: this.projectionReadiness.isReady(),
			socketIoAdapter: this.socketIoRedis.isReady(),
		};
		const socketIoAdapterDetails = this.socketIoRedis.getStatus();
		const projectionDetails = this.projectionReadiness.getDetailedStatus();
		const searchIndex = {
			ready: this.elasticsearchService.isReady(),
			lastError: this.elasticsearchService.getLastBootstrapError(),
		};

		const isReady = Object.values(dependencies).every(Boolean);
		if (!isReady) {
			throw new HttpException(
				{
					status: 'DOWN', dependencies, projectionDetails, socketIoAdapterDetails, searchIndex,
				},
				HttpStatus.SERVICE_UNAVAILABLE,
			);
		}

		return {
			status: 'UP', dependencies, projectionDetails, socketIoAdapterDetails, searchIndex,
		};
	}
}
