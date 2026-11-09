import {ConflictException} from '@nestjs/common';
import {BlockService, MAX_BLOCKED_USERS} from './block.service.js';
import type {BlockRedis} from './block.redis.js';

describe('BlockService.blockUser', () => {
	it('상한 안에서 차단이 반영되면 예외 없이 끝난다', async () => {
		const blockRedis = {block: jest.fn().mockResolvedValue(true)};
		const service = new BlockService(blockRedis as unknown as BlockRedis);

		await expect(service.blockUser(1, 2)).resolves.toBeUndefined();
		expect(blockRedis.block).toHaveBeenCalledWith(1, 2, MAX_BLOCKED_USERS);
	});

	it('차단 목록이 가득 차면 ConflictException을 던진다', async () => {
		const blockRedis = {block: jest.fn().mockResolvedValue(false)};
		const service = new BlockService(blockRedis as unknown as BlockRedis);

		await expect(service.blockUser(1, 2)).rejects.toBeInstanceOf(ConflictException);
	});
});
