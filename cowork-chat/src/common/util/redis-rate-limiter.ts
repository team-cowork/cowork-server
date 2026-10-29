import {randomUUID} from 'node:crypto';
import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
	ServiceUnavailableException,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Redis, ClientContext, Result} from 'ioredis';
import {getOptionalConfig, getRequiredConfig} from '../config/config.util.js';

// This.client에만 캐스팅해서 붙이는 타입 — ioredis의 공유 RedisCommander를 전역으로 확장하면
// defineCommand를 호출하지 않은 다른 Redis 클라이언트에도 이 메서드가 타입상 노출되어 버린다.
type WithTryAcquireScript<Context extends ClientContext = {type: 'default'}> = {
	tryAcquireScript(
		key: string,
		windowStart: number,
		now: number,
		member: string,
		windowMs: number,
		maxRequests: number,
	): Result<number, Context>;
	tryReserveScript(key: string, holder: string, ttlMs: number): Result<number, Context>;
	releaseScript(key: string, holder: string): Result<number, Context>;
	webhookAcquireScript(key: string, windowMs: number, limit: number, member: string): Result<number, Context>;
};
type RateLimiterRedis = Redis & WithTryAcquireScript;

export type ReservationResult = 'RESERVED' | 'HELD_BY_SAME_HOLDER' | 'BUSY';

// KEYS[1]=key ARGV[1]=holder ARGV[2]=ttlMs
const TRY_RESERVE_SCRIPT = `
if redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2], 'NX') then
    return 1
end
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return 2
end
return 0
`;

// KEYS[1]=key ARGV[1]=holder
const RELEASE_SCRIPT = `
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
`;

// KEYS[1]=key ARGV[1]=windowStart ARGV[2]=now ARGV[3]=member ARGV[4]=windowMs ARGV[5]=maxRequests
const TRY_ACQUIRE_SCRIPT = `
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[5]) then
    return 0
end
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
redis.call('PEXPIRE', KEYS[1], ARGV[4])
return 1
`;

// Both modes share keys and retention; switching modes cannot grant a fresh quota.
const WEBHOOK_ACQUIRE_SCRIPT = `
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
local count = redis.call('ZCARD', KEYS[1])
if count >= limit then
    local release = redis.call('ZRANGE', KEYS[1], count - limit, count - limit, 'WITHSCORES')
    return tonumber(release[2]) + window - now
end
redis.call('ZADD', KEYS[1], now, ARGV[3])
redis.call('PEXPIRE', KEYS[1], window)
return 0
`;

/**
 Redis Sorted Set 기반 슬라이딩 윈도우 rate limiter.
 키를 인스턴스 간에 공유하므로, 분산 멀티 인스턴스 환경에서도 사용자별 한도가 일관되게 적용된다
 (in-memory `Map` 기반 구현과 달리 인스턴스별로 한도가 개별 적용되어 우회되는 문제가 없다).
 정리·개수 확인·추가·만료를 Lua 스크립트로 서버 사이드에서 원자적으로 처리해 왕복을 1회로 줄이고,
 한도 초과 시 되돌리기 위한 별도 `ZREM` 호출(비원자적)도 제거한다.
 Redis 장애 시에는 rate limit 기능을 비활성화(fail-open)하고 요청을 허용한다 —
 핵심 메시징 기능이 rate limiter의 가용성에 종속되지 않도록 하기 위함이다.
 인증 없는 외부 입력도 받는 `tryAcquireWebhook`은 예외로 Redis 장애 시 요청을 거부한다.
 */
@injectable()
export class RedisRateLimiter implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(RedisRateLimiter.name);
	private client!: RateLimiterRedis;

	constructor(private readonly configService: ConfigService) {}

	onModuleInit(): void {
		const host = getRequiredConfig(this.configService, ['REDIS_HOST', 'redis.host']);
		const port = Number(getOptionalConfig(this.configService, ['REDIS_PORT', 'redis.port']) ?? 6379);

		this.client = new Redis({host, port, lazyConnect: true}) as RateLimiterRedis;
		this.client.defineCommand('tryAcquireScript', {numberOfKeys: 1, lua: TRY_ACQUIRE_SCRIPT});
		this.client.defineCommand('tryReserveScript', {numberOfKeys: 1, lua: TRY_RESERVE_SCRIPT});
		this.client.defineCommand('releaseScript', {numberOfKeys: 1, lua: RELEASE_SCRIPT});
		this.client.defineCommand('webhookAcquireScript', {numberOfKeys: 1, lua: WEBHOOK_ACQUIRE_SCRIPT});
		// Ioredis는 'error' 리스너가 없으면 unhandled error event로 프로세스가 죽는다
		this.client.on('error', (error: unknown) => {
			this.logger.error(`Redis client error: ${error instanceof Error ? error.message : String(error)}`);
		});
		void this.client.connect().catch((error: unknown) => {
			this.logger.warn(`Redis initial connection failed: ${error instanceof Error ? error.message : String(error)}`);
		});
	}

	onModuleDestroy(): void {
		this.client?.disconnect();
	}

	/**
    Redis 연결 상태를 확인한다. PING 실패 시 `false`를 반환한다 (readiness 체크용, fail-open 대상 아님).
    */
	async ping(): Promise<boolean> {
		try {
			return (await this.client.ping()) === 'PONG';
		} catch {
			return false;
		}
	}

	/**
     `key`에 대해 `windowMs` 시간창 내 `maxRequests` 한도를 초과하지 않았는지 검사하고, 소비한다.
     만료된 항목 제거·개수 확인·항목 추가·TTL 갱신을 Lua 스크립트 하나로 원자적으로 처리한다
     (한도 초과 시에는 애초에 ZADD를 실행하지 않으므로 되돌리기용 별도 호출이 필요 없다).

     @returns 한도 내면 `true`, 초과했으면 `false`. Redis 오류 시에는 `true`(fail-open)를 반환한다.
     */
	async tryAcquire(key: string, windowMs: number, maxRequests: number): Promise<boolean> {
		const now = Date.now();
		const windowStart = now - windowMs;
		const member = `${now}-${randomUUID()}`;

		try {
			const acquired = await this.client.tryAcquireScript(key, windowStart, now, member, windowMs, maxRequests);
			return acquired === 1;
		} catch (error) {
			this.logger.warn(`Rate limit check failed, failing open [key=${key}]`, error);
			return true;
		}
	}

	async tryAcquireWebhook(key: string, windowMs: number, limit: number): Promise<number> {
		if (this.client.status !== 'ready') {
			throw new ServiceUnavailableException('웹훅 요청 제한을 확인할 수 없습니다.');
		}

		let timer: ReturnType<typeof setTimeout> | undefined;
		try {
			return await Promise.race([
				this.client.webhookAcquireScript(`${key}:all`, windowMs, limit, randomUUID()),
				new Promise<never>((_resolve, reject) => {
					timer = setTimeout(() => {
						reject(new Error('Webhook rate limiter timed out'));
					}, 2000);
				}),
			]);
		} catch {
			throw new ServiceUnavailableException('웹훅 요청 제한을 확인할 수 없습니다.');
		} finally {
			if (timer !== undefined) {
				clearTimeout(timer);
			}
		}
	}

	/**
     `key`가 비어 있으면 `holder`로 `ttlMs` 동안 점유한다. 같은 `holder`가 이미 점유 중이면 재시도로 보고 통과시킨다.

     @returns Redis 오류 시에는 `RESERVED`(fail-open)를 반환한다.
     */
	async tryReserve(key: string, holder: string, ttlMs: number): Promise<ReservationResult> {
		try {
			const result = await this.client.tryReserveScript(key, holder, ttlMs);
			if (result === 1) {
				return 'RESERVED';
			}

			return result === 2 ? 'HELD_BY_SAME_HOLDER' : 'BUSY';
		} catch (error) {
			this.logger.warn(`Reservation failed, failing open [key=${key}]`, error);
			return 'RESERVED';
		}
	}

	/**
    `holder`가 점유한 경우에만 해제해 그 사이 다른 요청이 잡은 점유를 지우지 않는다.
    */
	async release(key: string, holder: string): Promise<void> {
		try {
			await this.client.releaseScript(key, holder);
		} catch (error) {
			this.logger.warn(`Reservation release failed [key=${key}]`, error);
		}
	}
}
