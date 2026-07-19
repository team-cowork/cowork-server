import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import Redis from 'ioredis';
import { getOptionalConfig, getRequiredConfig } from '../common/config/config.util';

const BLOCK_KEY_PREFIX = 'dm:block:';

@Injectable()
export class BlockRedis implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(BlockRedis.name);
    private client!: Redis;

    constructor(private readonly configService: ConfigService) {}

    onModuleInit(): void {
        const host = getRequiredConfig(this.configService, ['REDIS_HOST', 'redis.host']);
        const port = Number(getOptionalConfig(this.configService, ['REDIS_PORT', 'redis.port']) ?? 6379);

        this.client = new Redis({ host, port, lazyConnect: true });
        void this.client.connect().catch((err: unknown) => {
            this.logger.warn(`Redis initial connection failed: ${err instanceof Error ? err.message : String(err)}`);
        });
    }

    onModuleDestroy(): void {
        this.client.disconnect();
    }

    async block(blockerId: number, targetId: number): Promise<void> {
        await this.client.sadd(`${BLOCK_KEY_PREFIX}${blockerId}`, String(targetId));
    }

    async unblock(blockerId: number, targetId: number): Promise<void> {
        await this.client.srem(`${BLOCK_KEY_PREFIX}${blockerId}`, String(targetId));
    }

    async isBlocked(blockerId: number, targetId: number): Promise<boolean> {
        const result = await this.client.sismember(`${BLOCK_KEY_PREFIX}${blockerId}`, String(targetId));
        return result === 1;
    }

    async getBlockedIds(blockerId: number): Promise<number[]> {
        const members = await this.client.smembers(`${BLOCK_KEY_PREFIX}${blockerId}`);
        return members.map(Number);
    }
}
