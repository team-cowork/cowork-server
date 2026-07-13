import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import Redis from 'ioredis';
import { getOptionalConfig, getRequiredConfig } from '../../common/config/config.util';

const KEY_PREFIX = 'channel:meta:';
const TTL_SECONDS = 30;

/**
 * `ChannelClient.getChannel` 조회 결과(`viewType`)를 캐싱하는 Redis 기반 캐시.
 * Redis 조회/기록 실패 시에는 캐시 미스로 간주해 항상 원격 조회로 폴백한다(fail-open).
 */
@Injectable()
export class ChannelMetaCache implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChannelMetaCache.name);
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

    /**
     * 캐시된 채널 `viewType`을 조회한다.
     *
     * @returns 캐시 적중 시 `viewType` 문자열, 캐시 미스이거나 Redis 오류 발생 시 `null`
     */
    async get(channelId: number): Promise<string | null> {
        try {
            return await this.client.get(`${KEY_PREFIX}${channelId}`);
        } catch (err) {
            this.logger.warn(`Redis get failed, treating as cache miss [channelId=${channelId}]`, err);
            return null;
        }
    }

    async set(channelId: number, viewType: string): Promise<void> {
        try {
            await this.client.set(`${KEY_PREFIX}${channelId}`, viewType, 'EX', TTL_SECONDS);
        } catch (err) {
            this.logger.warn(`Redis set failed [channelId=${channelId}]`, err);
        }
    }

    /**
     * 채널 변경/삭제 이벤트 수신 시 캐시를 무효화한다.
     */
    async invalidate(channelId: number): Promise<void> {
        try {
            await this.client.del(`${KEY_PREFIX}${channelId}`);
        } catch (err) {
            this.logger.warn(`Redis invalidate failed [channelId=${channelId}]`, err);
        }
    }
}
