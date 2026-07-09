import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import Redis from 'ioredis';
import { getOptionalConfig, getRequiredConfig } from '../../common/config/config.util';

const KEY_PREFIX = 'unread:';
const TTL_SECONDS = 86_400;

export interface UnreadCacheResult {
    hits: Map<number, number>;
    misses: number[];
}

/**
 * 채널별 안읽음 메시지 수를 캐싱하는 Redis 기반 cache-aside 카운터.
 *
 * `unread:{userId}` 해시에 `channelId` → count를 저장한다. Redis는 소모성 캐시일 뿐이며,
 * 정확한 값의 원천은 여전히 MongoDB 집계(`MessageRepository.countUnreadForChannels`/`countUnread`)다.
 * 캐시 미스나 Redis 오류는 항상 호출부가 MongoDB로 폴백하도록 신호를 보낸다(fail-open).
 *
 * 증가(`incrementIfPresent`)는 필드가 이미 존재하는 유저에게만 적용된다 — 존재하지 않는 채널에
 * 무작정 1부터 증가시키면 실제 안읽음 수(과거 누적분)를 무시한 잘못된 값이 캐시에 고정되기 때문이다.
 * 필드가 없는 경우는 그대로 비워두고, 다음 조회 시 캐시미스 폴백이 정확한 값을 계산해 채운다.
 */
@Injectable()
export class UnreadCounterService implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(UnreadCounterService.name);
    private client!: Redis;

    constructor(private readonly configService: ConfigService) {}

    onModuleInit(): void {
        const host = getRequiredConfig(this.configService, ['REDIS_HOST', 'redis.host']);
        const port = Number(getOptionalConfig(this.configService, ['REDIS_PORT', 'redis.port']) ?? 6379);

        this.client = new Redis({
            host,
            port,
            lazyConnect: true,
            enableOfflineQueue: false,
            maxRetriesPerRequest: 0,
        });
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

    private key(userId: number): string {
        return `${KEY_PREFIX}${userId}`;
    }

    /**
     * 여러 채널의 캐시된 안읽음 수를 조회한다.
     *
     * @returns 캐시에 값이 있는 채널은 `hits`, 없는(또는 Redis 오류로 판단 불가한) 채널은 `misses`.
     *          Redis 자체가 오류를 던지면 `null`을 반환해 호출부가 전체를 미스로 간주하고 폴백하게 한다.
     */
    async getMany(userId: number, channelIds: number[]): Promise<UnreadCacheResult | null> {
        if (channelIds.length === 0) return { hits: new Map(), misses: [] };
        try {
            const values = await this.client.hmget(this.key(userId), ...channelIds.map(String));
            const hits = new Map<number, number>();
            const misses: number[] = [];
            channelIds.forEach((channelId, i) => {
                const raw = values[i];
                if (raw === null || raw === undefined) {
                    misses.push(channelId);
                } else {
                    hits.set(channelId, Number(raw));
                }
            });
            return { hits, misses };
        } catch (err) {
            this.logger.warn(`Redis getMany failed, treating as full cache miss [userId=${userId}]`, err);
            return null;
        }
    }

    /**
     * 여러 채널의 안읽음 수를 캐시에 채운다 (캐시미스 폴백 계산 결과를 기록할 때 사용).
     */
    async setMany(userId: number, values: Map<number, number>): Promise<void> {
        if (values.size === 0) return;
        try {
            const args: string[] = [];
            for (const [channelId, count] of values) {
                args.push(String(channelId), String(count));
            }
            const key = this.key(userId);
            await this.client.pipeline().hset(key, ...args).expire(key, TTL_SECONDS).exec();
        } catch (err) {
            this.logger.warn(`Redis setMany failed [userId=${userId}]`, err);
        }
    }

    /**
     * 단일 채널의 안읽음 수를 정확한 값으로 설정한다 (읽음 처리 시 사용).
     */
    async set(channelId: number, userId: number, count: number): Promise<void> {
        try {
            const key = this.key(userId);
            await this.client.pipeline().hset(key, String(channelId), String(count)).expire(key, TTL_SECONDS).exec();
        } catch (err) {
            this.logger.warn(`Redis set failed [channelId=${channelId}, userId=${userId}]`, err);
        }
    }

    /**
     * 새 메시지 발생 시 해당 채널 필드가 이미 캐시에 존재하는 유저에 대해서만 안읽음 수를 1 증가시킨다.
     * 필드가 없는(캐시 미스 상태인) 유저는 건드리지 않는다 — 다음 조회의 캐시미스 폴백이 처리한다.
     */
    async incrementIfPresent(channelId: number, userIds: number[]): Promise<void> {
        if (userIds.length === 0) return;
        try {
            const field = String(channelId);
            const existsPipeline = this.client.pipeline();
            for (const userId of userIds) {
                existsPipeline.hexists(this.key(userId), field);
            }
            const existsResults = await existsPipeline.exec();
            const presentUserIds = userIds.filter((_, i) => existsResults?.[i]?.[1] === 1);
            if (presentUserIds.length === 0) return;

            const incrPipeline = this.client.pipeline();
            for (const userId of presentUserIds) {
                incrPipeline.hincrby(this.key(userId), field, 1);
            }
            await incrPipeline.exec();
        } catch (err) {
            this.logger.warn(`Redis incrementIfPresent failed [channelId=${channelId}]`, err);
        }
    }
}
