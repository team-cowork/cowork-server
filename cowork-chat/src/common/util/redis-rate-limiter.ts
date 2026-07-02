import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import Redis from 'ioredis';
import { randomUUID } from 'crypto';
import { getOptionalConfig, getRequiredConfig } from '../config/config.util';

/**
 * Redis Sorted Set 기반 슬라이딩 윈도우 rate limiter.
 * 키를 인스턴스 간에 공유하므로, 분산 멀티 인스턴스 환경에서도 사용자별 한도가 일관되게 적용된다
 * (in-memory `Map` 기반 구현과 달리 인스턴스별로 한도가 개별 적용되어 우회되는 문제가 없다).
 * Redis 장애 시에는 rate limit 기능을 비활성화(fail-open)하고 요청을 허용한다 —
 * 핵심 메시징 기능이 rate limiter의 가용성에 종속되지 않도록 하기 위함이다.
 */
@Injectable()
export class RedisRateLimiter implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(RedisRateLimiter.name);
    private client!: Redis;

    constructor(private readonly configService: ConfigService) {}

    onModuleInit(): void {
        const host = getRequiredConfig(this.configService, ['REDIS_HOST', 'redis.host']);
        const port = Number(getOptionalConfig(this.configService, ['REDIS_PORT', 'redis.port']) ?? 6379);

        this.client = new Redis({ host, port, lazyConnect: true });
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

    /**
     * `key`에 대해 `windowMs` 시간창 내 `maxRequests` 한도를 초과하지 않았는지 검사하고, 소비한다.
     * 만료된 항목 제거·개수 확인·항목 추가·TTL 갱신을 하나의 Redis 트랜잭션(MULTI/EXEC)으로 처리해
     * 동시 요청 간 경쟁 상태 없이 원자적으로 동작한다.
     *
     * @returns 한도 내면 `true`, 초과했으면 `false`. Redis 오류 시에는 `true`(fail-open)를 반환한다.
     */
    async tryAcquire(key: string, windowMs: number, maxRequests: number): Promise<boolean> {
        const now = Date.now();
        const windowStart = now - windowMs;
        const member = `${now}-${randomUUID()}`;

        try {
            const results = await this.client
                .multi()
                .zremrangebyscore(key, 0, windowStart)
                .zcard(key)
                .zadd(key, now, member)
                .pexpire(key, windowMs)
                .exec();

            const countBeforeAdd = Number(results?.[1]?.[1] ?? 0);
            if (countBeforeAdd >= maxRequests) {
                // 정리(cleanup) 실패가 이 판정(한도 초과)을 fail-open으로 뒤집지 않도록 await하지 않는다
                void this.client.zrem(key, member).catch((err: unknown) => {
                    this.logger.warn(`Failed to clean up rate limit member: key=${key}, error=${err instanceof Error ? err.message : String(err)}`);
                });
                return false;
            }
            return true;
        } catch (error) {
            this.logger.warn(`Rate limit check failed, failing open [key=${key}]`, error);
            return true;
        }
    }
}
