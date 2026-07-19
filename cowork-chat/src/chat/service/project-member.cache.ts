import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import Redis from 'ioredis';
import { getOptionalConfig, getRequiredConfig } from '../../common/config/config.util';

const KEY_PREFIX = 'project:member:';
const TTL_SECONDS = 30;

/**
 * `ProjectClient.isMember` 조회 결과를 캐싱하는 Redis 기반 캐시.
 * Redis 조회/기록 실패 시에는 캐시 미스로 간주해 항상 원격 조회로 폴백한다(fail-open).
 */
@Injectable()
export class ProjectMemberCache implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ProjectMemberCache.name);
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
     * 캐시된 멤버십 여부를 조회한다.
     *
     * @returns 캐시 적중 시 `boolean`, 캐시 미스이거나 Redis 오류 발생 시 `null`
     */
    async get(projectId: number, userId: number): Promise<boolean | null> {
        try {
            const value = await this.client.get(`${KEY_PREFIX}${projectId}:${userId}`);
            if (value === null) return null;
            return value === '1';
        } catch (err) {
            this.logger.warn(`Redis get failed, treating as cache miss [projectId=${projectId}, userId=${userId}]`, err);
            return null;
        }
    }

    async set(projectId: number, userId: number, isMember: boolean): Promise<void> {
        try {
            await this.client.set(`${KEY_PREFIX}${projectId}:${userId}`, isMember ? '1' : '0', 'EX', TTL_SECONDS);
        } catch (err) {
            this.logger.warn(`Redis set failed [projectId=${projectId}, userId=${userId}]`, err);
        }
    }
}
