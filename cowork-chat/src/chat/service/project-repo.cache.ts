import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import Redis from 'ioredis';
import { getOptionalConfig, getRequiredConfig } from '../../common/config/config.util';
import type { GithubRepoInfo } from './project.client';

const KEY_PREFIX = 'project:repo:';
const TTL_SECONDS = 30;

/**
 * `ProjectClient.getGithubRepoInfo` 조회 결과를 캐싱하는 Redis 기반 캐시.
 * 저장소 정보가 없는 프로젝트(`null`)도 캐싱 대상이다 — 반복 조회 비용을 줄이기 위함.
 * Redis 조회/기록 실패 시에는 캐시 미스로 간주해 항상 원격 조회로 폴백한다(fail-open).
 */
@Injectable()
export class ProjectRepoCache implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ProjectRepoCache.name);
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
     * 캐시된 GitHub 저장소 정보를 조회한다.
     *
     * @returns 캐시 적중 시 `GithubRepoInfo | null`, 캐시 미스이거나 Redis 오류 발생 시 `undefined`
     */
    async get(projectId: number): Promise<GithubRepoInfo | null | undefined> {
        try {
            const value = await this.client.get(`${KEY_PREFIX}${projectId}`);
            if (value === null) return undefined;
            return JSON.parse(value) as GithubRepoInfo | null;
        } catch (err) {
            this.logger.warn(`Redis get failed, treating as cache miss [projectId=${projectId}]`, err);
            return undefined;
        }
    }

    async set(projectId: number, repoInfo: GithubRepoInfo | null): Promise<void> {
        try {
            await this.client.set(`${KEY_PREFIX}${projectId}`, JSON.stringify(repoInfo), 'EX', TTL_SECONDS);
        } catch (err) {
            this.logger.warn(`Redis set failed [projectId=${projectId}]`, err);
        }
    }

    /**
     * 프로젝트 변경/삭제 이벤트 수신 시 캐시를 무효화한다.
     */
    async invalidate(projectId: number): Promise<void> {
        try {
            await this.client.del(`${KEY_PREFIX}${projectId}`);
        } catch (err) {
            this.logger.warn(`Redis invalidate failed [projectId=${projectId}]`, err);
        }
    }
}
