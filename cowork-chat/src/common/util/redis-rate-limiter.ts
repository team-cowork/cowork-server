import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import Redis, { ClientContext, Result } from 'ioredis';
import { randomUUID } from 'crypto';
import { getOptionalConfig, getRequiredConfig } from '../config/config.util';

// this.client에만 캐스팅해서 붙이는 타입 — ioredis의 공유 RedisCommander를 전역으로 확장하면
// defineCommand를 호출하지 않은 다른 Redis 클라이언트에도 이 메서드가 타입상 노출되어 버린다.
interface WithTryAcquireScript<Context extends ClientContext = { type: 'default' }> {
    tryAcquireScript(
        key: string,
        windowStart: number,
        now: number,
        member: string,
        windowMs: number,
        maxRequests: number,
    ): Result<number, Context>;
}
type RateLimiterRedis = Redis & WithTryAcquireScript;

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

/**
 * Redis Sorted Set 기반 슬라이딩 윈도우 rate limiter.
 * 키를 인스턴스 간에 공유하므로, 분산 멀티 인스턴스 환경에서도 사용자별 한도가 일관되게 적용된다
 * (in-memory `Map` 기반 구현과 달리 인스턴스별로 한도가 개별 적용되어 우회되는 문제가 없다).
 * 정리·개수 확인·추가·만료를 Lua 스크립트로 서버 사이드에서 원자적으로 처리해 왕복을 1회로 줄이고,
 * 한도 초과 시 되돌리기 위한 별도 `ZREM` 호출(비원자적)도 제거한다.
 * Redis 장애 시에는 rate limit 기능을 비활성화(fail-open)하고 요청을 허용한다 —
 * 핵심 메시징 기능이 rate limiter의 가용성에 종속되지 않도록 하기 위함이다.
 */
@Injectable()
export class RedisRateLimiter implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(RedisRateLimiter.name);
    private client!: RateLimiterRedis;

    constructor(private readonly configService: ConfigService) {}

    onModuleInit(): void {
        const host = getRequiredConfig(this.configService, ['REDIS_HOST', 'redis.host']);
        const port = Number(getOptionalConfig(this.configService, ['REDIS_PORT', 'redis.port']) ?? 6379);

        this.client = new Redis({ host, port, lazyConnect: true }) as RateLimiterRedis;
        this.client.defineCommand('tryAcquireScript', { numberOfKeys: 1, lua: TRY_ACQUIRE_SCRIPT });
        // ioredis는 'error' 리스너가 없으면 unhandled error event로 프로세스가 죽는다
        this.client.on('error', (err: unknown) => {
            this.logger.error(`Redis client error: ${err instanceof Error ? err.message : String(err)}`);
        });
        void this.client.connect().catch((err: unknown) => {
            this.logger.warn(`Redis initial connection failed: ${err instanceof Error ? err.message : String(err)}`);
        });
    }

    onModuleDestroy(): void {
        this.client?.disconnect();
    }

    /** Redis 연결 상태를 확인한다. PING 실패 시 `false`를 반환한다 (readiness 체크용, fail-open 대상 아님). */
    async ping(): Promise<boolean> {
        try {
            return (await this.client.ping()) === 'PONG';
        } catch {
            return false;
        }
    }

    /**
     * `key`에 대해 `windowMs` 시간창 내 `maxRequests` 한도를 초과하지 않았는지 검사하고, 소비한다.
     * 만료된 항목 제거·개수 확인·항목 추가·TTL 갱신을 Lua 스크립트 하나로 원자적으로 처리한다
     * (한도 초과 시에는 애초에 ZADD를 실행하지 않으므로 되돌리기용 별도 호출이 필요 없다).
     *
     * @returns 한도 내면 `true`, 초과했으면 `false`. Redis 오류 시에는 `true`(fail-open)를 반환한다.
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
}
