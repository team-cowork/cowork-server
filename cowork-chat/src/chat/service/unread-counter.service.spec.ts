import { ConfigService } from '@nestjs/config';
import { UnreadCounterService } from './unread-counter.service';

const mockHmget = jest.fn();
const mockIncrementIfPresentScript = jest.fn().mockReturnThis();
const mockPipelineExec = jest.fn().mockResolvedValue(undefined);
const mockPipeline = {
    hset: jest.fn().mockReturnThis(),
    expire: jest.fn().mockReturnThis(),
    incrementIfPresentScript: mockIncrementIfPresentScript,
    exec: mockPipelineExec,
};
const mockPipelineFn = jest.fn(() => mockPipeline);
const mockDefineCommand = jest.fn();
const mockConnect = jest.fn().mockResolvedValue(undefined);
const mockDisconnect = jest.fn();
const mockOn = jest.fn();

jest.mock('ioredis', () => jest.fn().mockImplementation(() => ({
    hmget: mockHmget,
    pipeline: mockPipelineFn,
    defineCommand: mockDefineCommand,
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
        mockPipelineExec.mockResolvedValue(undefined);
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
        it('값이 있으면 파이프라인으로 hset과 expire를 한 번에 실행한다', async () => {
            await service.setMany(1, new Map([[10, 3], [20, 0]]));

            expect(mockPipeline.hset).toHaveBeenCalledWith('unread:1', '10', '3', '20', '0');
            expect(mockPipeline.expire).toHaveBeenCalledWith('unread:1', 86_400);
            expect(mockPipelineExec).toHaveBeenCalled();
        });

        it('빈 Map이면 파이프라인을 실행하지 않는다', async () => {
            await service.setMany(1, new Map());

            expect(mockPipelineFn).not.toHaveBeenCalled();
        });

        it('Redis 오류가 발생해도 예외를 던지지 않는다', async () => {
            mockPipelineExec.mockRejectedValueOnce(new Error('connection lost'));

            await expect(service.setMany(1, new Map([[10, 3]]))).resolves.toBeUndefined();
        });
    });

    describe('set', () => {
        it('단일 채널 값을 파이프라인으로 hset과 expire를 한 번에 실행한다', async () => {
            await service.set(10, 1, 5);

            expect(mockPipeline.hset).toHaveBeenCalledWith('unread:1', '10', '5');
            expect(mockPipeline.expire).toHaveBeenCalledWith('unread:1', 86_400);
            expect(mockPipelineExec).toHaveBeenCalled();
        });

        it('Redis 오류가 발생해도 예외를 던지지 않는다', async () => {
            mockPipelineExec.mockRejectedValueOnce(new Error('connection lost'));

            await expect(service.set(10, 1, 5)).resolves.toBeUndefined();
        });
    });

    describe('incrementIfPresent', () => {
        it('userIds가 비어 있으면 아무것도 하지 않는다', async () => {
            await service.incrementIfPresent(10, []);

            expect(mockPipelineFn).not.toHaveBeenCalled();
        });

        it('청크 크기 이하면 파이프라인에 스크립트 호출 한 번만 담아 실행한다', async () => {
            await service.incrementIfPresent(10, [1, 2, 3]);

            expect(mockIncrementIfPresentScript).toHaveBeenCalledTimes(1);
            expect(mockIncrementIfPresentScript).toHaveBeenCalledWith('10', 'unread:1', 'unread:2', 'unread:3');
            expect(mockPipelineExec).toHaveBeenCalledTimes(1);
        });

        it('유저 수가 청크 크기를 넘으면 여러 스크립트 호출로 나눠 파이프라인 한 번에 담아 실행한다', async () => {
            const userIds = Array.from({ length: 250 }, (_, i) => i + 1);

            await service.incrementIfPresent(10, userIds);

            expect(mockIncrementIfPresentScript).toHaveBeenCalledTimes(3);
            expect(mockIncrementIfPresentScript).toHaveBeenNthCalledWith(
                1,
                '10',
                ...userIds.slice(0, 100).map((id) => `unread:${id}`),
            );
            expect(mockIncrementIfPresentScript).toHaveBeenNthCalledWith(
                2,
                '10',
                ...userIds.slice(100, 200).map((id) => `unread:${id}`),
            );
            expect(mockIncrementIfPresentScript).toHaveBeenNthCalledWith(
                3,
                '10',
                ...userIds.slice(200, 250).map((id) => `unread:${id}`),
            );
            expect(mockPipelineExec).toHaveBeenCalledTimes(1);
        });

        it('Redis 오류가 발생해도 예외를 던지지 않는다', async () => {
            mockPipelineExec.mockRejectedValueOnce(new Error('connection lost'));

            await expect(service.incrementIfPresent(10, [1, 2])).resolves.toBeUndefined();
        });
    });

    it('onModuleInit 시 incrementIfPresentScript Lua 스크립트를 등록한다', () => {
        expect(mockDefineCommand).toHaveBeenCalledWith(
            'incrementIfPresentScript',
            expect.objectContaining({ numberOfKeys: 0, lua: expect.any(String) as unknown }),
        );
    });

    it('onModuleDestroy 호출 시 클라이언트 연결을 해제한다', () => {
        service.onModuleDestroy();
        expect(mockDisconnect).toHaveBeenCalled();
    });
});
