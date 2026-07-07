import { ConfigService } from '@nestjs/config';
import { UnreadCounterService } from './unread-counter.service';

const mockHmget = jest.fn();
const mockHset = jest.fn().mockResolvedValue(undefined);
const mockExpire = jest.fn().mockResolvedValue(1);
const mockPipelineExec = jest.fn();
const mockPipeline = {
    hexists: jest.fn().mockReturnThis(),
    hincrby: jest.fn().mockReturnThis(),
    exec: mockPipelineExec,
};
const mockPipelineFn = jest.fn(() => mockPipeline);
const mockConnect = jest.fn().mockResolvedValue(undefined);
const mockDisconnect = jest.fn();
const mockOn = jest.fn();

jest.mock('ioredis', () => jest.fn().mockImplementation(() => ({
    hmget: mockHmget,
    hset: mockHset,
    expire: mockExpire,
    pipeline: mockPipelineFn,
    connect: mockConnect,
    disconnect: mockDisconnect,
    on: mockOn,
})));

const mockConfigService = {
    get: jest.fn((key: string) => {
        if (key === 'REDIS_HOST') return 'localhost';
        if (key === 'REDIS_PORT') return '6379';
        return undefined;
    }),
} as unknown as ConfigService;

describe('UnreadCounterService', () => {
    let service: UnreadCounterService;

    beforeEach(() => {
        jest.clearAllMocks();
        mockConnect.mockResolvedValue(undefined);
        mockHset.mockResolvedValue(undefined);
        mockExpire.mockResolvedValue(1);
        service = new UnreadCounterService(mockConfigService);
        service.onModuleInit();
    });

    describe('getMany', () => {
        it('빈 channelIds는 즉시 빈 결과를 반환한다', async () => {
            const result = await service.getMany(1, []);
            expect(result).toEqual({ hits: new Map(), misses: [] });
            expect(mockHmget).not.toHaveBeenCalled();
        });

        it('모든 채널이 캐시에 있으면 전부 hits로 반환한다', async () => {
            mockHmget.mockResolvedValue(['3', '0']);

            const result = await service.getMany(1, [10, 20]);

            expect(mockHmget).toHaveBeenCalledWith('unread:1', '10', '20');
            expect(result).toEqual({ hits: new Map([[10, 3], [20, 0]]), misses: [] });
        });

        it('일부 채널이 캐시에 없으면 misses에 담는다', async () => {
            mockHmget.mockResolvedValue(['3', null]);

            const result = await service.getMany(1, [10, 20]);

            expect(result).toEqual({ hits: new Map([[10, 3]]), misses: [20] });
        });

        it('Redis 오류가 발생하면 null을 반환한다', async () => {
            mockHmget.mockRejectedValue(new Error('connection lost'));

            const result = await service.getMany(1, [10, 20]);

            expect(result).toBeNull();
        });
    });

    describe('setMany', () => {
        it('값이 있으면 hset을 flatten된 인자로 호출하고 TTL을 설정한다', async () => {
            await service.setMany(1, new Map([[10, 3], [20, 0]]));

            expect(mockHset).toHaveBeenCalledWith('unread:1', '10', '3', '20', '0');
            expect(mockExpire).toHaveBeenCalledWith('unread:1', 86_400);
        });

        it('빈 Map이면 hset을 호출하지 않는다', async () => {
            await service.setMany(1, new Map());

            expect(mockHset).not.toHaveBeenCalled();
            expect(mockExpire).not.toHaveBeenCalled();
        });

        it('Redis 오류가 발생해도 예외를 던지지 않는다', async () => {
            mockHset.mockRejectedValue(new Error('connection lost'));

            await expect(service.setMany(1, new Map([[10, 3]]))).resolves.toBeUndefined();
        });
    });

    describe('set', () => {
        it('단일 채널 값을 hset으로 저장하고 TTL을 설정한다', async () => {
            await service.set(10, 1, 5);

            expect(mockHset).toHaveBeenCalledWith('unread:1', '10', '5');
            expect(mockExpire).toHaveBeenCalledWith('unread:1', 86_400);
        });

        it('Redis 오류가 발생해도 예외를 던지지 않는다', async () => {
            mockHset.mockRejectedValue(new Error('connection lost'));

            await expect(service.set(10, 1, 5)).resolves.toBeUndefined();
        });
    });

    describe('incrementIfPresent', () => {
        it('userIds가 비어 있으면 아무것도 하지 않는다', async () => {
            await service.incrementIfPresent(10, []);

            expect(mockPipelineFn).not.toHaveBeenCalled();
        });

        it('캐시 필드가 존재하는 유저만 증가시킨다', async () => {
            mockPipelineExec.mockResolvedValue([[null, 1], [null, 0], [null, 1]]);

            await service.incrementIfPresent(10, [1, 2, 3]);

            expect(mockPipeline.hexists).toHaveBeenCalledWith('unread:1', '10');
            expect(mockPipeline.hexists).toHaveBeenCalledWith('unread:2', '10');
            expect(mockPipeline.hexists).toHaveBeenCalledWith('unread:3', '10');
            expect(mockPipeline.hincrby).toHaveBeenCalledWith('unread:1', '10', 1);
            expect(mockPipeline.hincrby).toHaveBeenCalledWith('unread:3', '10', 1);
            expect(mockPipeline.hincrby).not.toHaveBeenCalledWith('unread:2', '10', 1);
        });

        it('아무도 캐시에 없으면 증가 파이프라인을 만들지 않는다', async () => {
            mockPipelineExec.mockResolvedValue([[null, 0], [null, 0]]);

            await service.incrementIfPresent(10, [1, 2]);

            expect(mockPipelineFn).toHaveBeenCalledTimes(1);
            expect(mockPipeline.hincrby).not.toHaveBeenCalled();
        });

        it('Redis 오류가 발생해도 예외를 던지지 않는다', async () => {
            mockPipelineExec.mockRejectedValue(new Error('connection lost'));

            await expect(service.incrementIfPresent(10, [1, 2])).resolves.toBeUndefined();
        });
    });

    it('onModuleDestroy 호출 시 클라이언트 연결을 해제한다', () => {
        service.onModuleDestroy();
        expect(mockDisconnect).toHaveBeenCalled();
    });
});
