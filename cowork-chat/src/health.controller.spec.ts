import { Test, TestingModule } from '@nestjs/testing';
import { getConnectionToken } from '@nestjs/mongoose';
import { HttpException } from '@nestjs/common';
import { HealthController } from './health.controller';
import { RedisRateLimiter } from './common/util/redis-rate-limiter';
import { ChatMessageProducer } from './chat/kafka/chat-message.producer';
import { ProjectionReadinessService } from './common/kafka/projection-readiness.service';

describe('HealthController', () => {
    let controller: HealthController;
    let mongoConnection: { readyState: number };
    let redisRateLimiter: { ping: jest.Mock };
    let chatMessageProducer: { isReady: jest.Mock };
    let projectionReadiness: { isReady: jest.Mock };

    beforeEach(async () => {
        mongoConnection = { readyState: 1 };
        redisRateLimiter = { ping: jest.fn().mockResolvedValue(true) };
        chatMessageProducer = { isReady: jest.fn().mockReturnValue(true) };
        projectionReadiness = { isReady: jest.fn().mockReturnValue(true) };

        const module: TestingModule = await Test.createTestingModule({
            controllers: [HealthController],
            providers: [
                { provide: getConnectionToken(), useValue: mongoConnection },
                { provide: RedisRateLimiter, useValue: redisRateLimiter },
                { provide: ChatMessageProducer, useValue: chatMessageProducer },
                { provide: ProjectionReadinessService, useValue: projectionReadiness },
            ],
        }).compile();

        controller = module.get(HealthController);
    });

    it('/health는 항상 UP을 반환한다', () => {
        expect(controller.health()).toEqual({ status: 'UP' });
    });

    it('모든 의존성이 정상이면 /health/ready는 UP을 반환한다', async () => {
        await expect(controller.ready()).resolves.toEqual({
            status: 'UP',
            dependencies: { mongo: true, redis: true, kafka: true, projections: true },
        });
    });

    it('MongoDB 연결이 끊기면 /health/ready는 503과 함께 DOWN을 반환한다', async () => {
        mongoConnection.readyState = 0;

        await expect(controller.ready()).rejects.toMatchObject({
            status: 503,
            response: { status: 'DOWN', dependencies: { mongo: false, redis: true, kafka: true, projections: true } },
        } as unknown as HttpException);
    });

    it('Redis PING이 실패하면 /health/ready는 503과 함께 DOWN을 반환한다', async () => {
        redisRateLimiter.ping.mockResolvedValue(false);

        await expect(controller.ready()).rejects.toThrow(HttpException);
    });

    it('Kafka producer가 연결되지 않았으면 /health/ready는 503과 함께 DOWN을 반환한다', async () => {
        chatMessageProducer.isReady.mockReturnValue(false);

        await expect(controller.ready()).rejects.toThrow(HttpException);
    });

    it('projection replay가 시작 high-watermark까지 끝나지 않았으면 /health/ready는 503을 반환한다', async () => {
        projectionReadiness.isReady.mockReturnValue(false);

        await expect(controller.ready()).rejects.toMatchObject({
            status: 503,
            response: {
                status: 'DOWN',
                dependencies: { mongo: true, redis: true, kafka: true, projections: false },
            },
        } as unknown as HttpException);
    });
});
