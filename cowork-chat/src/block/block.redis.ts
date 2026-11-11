import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Redis} from 'ioredis';
import {getOptionalConfig, getRequiredConfig} from '../common/config/config.util.js';

const BLOCK_KEY_PREFIX = 'dm:block:';

// KEYS[1]=key ARGV[1]=targetId ARGV[2]=limit
// 크기 확인과 추가를 한 스크립트로 묶어 동시 요청이 상한을 넘지 못하게 한다. 이미 차단된 대상은 상한과 무관하게 성공이다.
const BLOCK_SCRIPT = `
if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
    return 1
end
if redis.call('SCARD', KEYS[1]) >= tonumber(ARGV[2]) then
    return 0
end
redis.call('SADD', KEYS[1], ARGV[1])
return 1
`;

@injectable()
export class BlockRedis implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(BlockRedis.name);
	private client!: Redis;

	constructor(private readonly configService: ConfigService) {}

	onModuleInit(): void {
		const host = getRequiredConfig(this.configService, ['REDIS_HOST', 'redis.host']);
		const port = Number(getOptionalConfig(this.configService, ['REDIS_PORT', 'redis.port']) ?? 6379);

		this.client = new Redis({host, port, lazyConnect: true});
		void this.client.connect().catch((error: unknown) => {
			this.logger.warn(`Redis initial connection failed: ${error instanceof Error ? error.message : String(error)}`);
		});
	}

	onModuleDestroy(): void {
		this.client.disconnect();
	}

	/**
     사용자 차단을 Redis 집합에 추가한다.

     @returns 차단이 반영되면 `true`, 차단 목록이 `limit`에 도달해 추가하지 못했으면 `false`
     */
	async block(blockerId: number, targetId: number, limit: number): Promise<boolean> {
		const result = await this.client.eval(BLOCK_SCRIPT, 1, `${BLOCK_KEY_PREFIX}${blockerId}`, String(targetId), String(limit));
		return result === 1;
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
