import { BlockService } from './block.service';
import { BlockRedis } from './block.redis';
import { BlockProducer } from '../kafka/block.producer';

const mockBlockRedis = () => ({
    block: jest.fn(),
    unblock: jest.fn(),
    isBlocked: jest.fn(),
    getBlockedIds: jest.fn(),
});

const mockBlockProducer = () => ({
    send: jest.fn(),
});

describe('BlockService', () => {
    let service: BlockService;
    let redis: ReturnType<typeof mockBlockRedis>;
    let producer: ReturnType<typeof mockBlockProducer>;

    beforeEach(() => {
        redis = mockBlockRedis();
        producer = mockBlockProducer();
        service = new BlockService(
            redis as unknown as BlockRedis,
            producer as unknown as BlockProducer,
        );
    });

    describe('blockUser', () => {
        it('Redis에 차단 저장 후 Kafka 이벤트 발행', async () => {
            redis.block.mockResolvedValue(undefined);
            producer.send.mockResolvedValue(undefined);

            await service.blockUser(1, 2);

            expect(redis.block).toHaveBeenCalledWith(1, 2);
            expect(producer.send).toHaveBeenCalledWith({ blockerId: 1, targetId: 2, action: 'BLOCK' });
        });
    });

    describe('unblockUser', () => {
        it('Redis에서 차단 해제 후 Kafka 이벤트 발행', async () => {
            redis.unblock.mockResolvedValue(undefined);
            producer.send.mockResolvedValue(undefined);

            await service.unblockUser(1, 2);

            expect(redis.unblock).toHaveBeenCalledWith(1, 2);
            expect(producer.send).toHaveBeenCalledWith({ blockerId: 1, targetId: 2, action: 'UNBLOCK' });
        });
    });

    describe('isBlocked', () => {
        it('차단된 경우 true 반환', async () => {
            redis.isBlocked.mockResolvedValue(true);

            const result = await service.isBlocked(2, 1);

            expect(result).toBe(true);
            expect(redis.isBlocked).toHaveBeenCalledWith(2, 1);
        });

        it('차단되지 않은 경우 false 반환', async () => {
            redis.isBlocked.mockResolvedValue(false);

            const result = await service.isBlocked(2, 1);

            expect(result).toBe(false);
        });
    });

    describe('getBlockedUsers', () => {
        it('차단한 사용자 목록 반환', async () => {
            redis.getBlockedIds.mockResolvedValue([2, 3, 4]);

            const result = await service.getBlockedUsers(1);

            expect(result).toEqual([2, 3, 4]);
            expect(redis.getBlockedIds).toHaveBeenCalledWith(1);
        });

        it('차단 목록이 없으면 빈 배열 반환', async () => {
            redis.getBlockedIds.mockResolvedValue([]);

            const result = await service.getBlockedUsers(1);

            expect(result).toEqual([]);
        });
    });
});
