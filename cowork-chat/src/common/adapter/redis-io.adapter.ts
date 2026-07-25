import { INestApplicationContext, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { IoAdapter } from '@nestjs/platform-socket.io';
import { Server, ServerOptions } from 'socket.io';
import { createAdapter } from '@socket.io/redis-adapter';
import Redis from 'ioredis';
import { getOptionalConfig, getRequiredConfig } from '../config/config.util';

const READY_TIMEOUT_MS = 5_000;

function timeout(ms: number): { promise: Promise<never>; cancel: () => void } {
    let timer: NodeJS.Timeout;
    const promise = new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error(`Redis ready timeout after ${ms}ms`)), ms);
    });
    return { promise, cancel: () => clearTimeout(timer) };
}

/**
 * Socket.IO 기본(in-memory) adapter는 room 브로드캐스트를 단일 프로세스 범위로만 처리한다.
 *
 * `cowork-chat`을 여러 replica로 띄우면(트래픽 대응을 위해 필연적으로 발생하며, Kafka consumer group의
 * 경쟁 컨슈머 특성상 각 메시지는 정확히 하나의 인스턴스에서만 처리됨), 그 인스턴스가 브로드캐스트한
 * `io.to(room).emit(...)`은 같은 인스턴스에 연결된 소켓에만 도달하고 다른 인스턴스에 연결된 클라이언트는
 * 조용히 이벤트를 받지 못한다. 이 adapter는 Redis pub/sub으로 인스턴스 간 브로드캐스트를 동기화해
 * 어느 인스턴스가 이벤트를 발행했든 모든 인스턴스의 소켓에 정상 전달되게 한다.
 */
export class RedisIoAdapter extends IoAdapter {
    private readonly logger = new Logger(RedisIoAdapter.name);
    private adapterConstructor?: ReturnType<typeof createAdapter>;

    constructor(app: INestApplicationContext) {
        super(app);
    }

    /**
     * Redis pub/sub 클라이언트 두 개를 생성하고 준비될 때까지 대기한다.
     * `createIOServer`가 호출되기 전에 반드시 완료되어야 한다.
     *
     * ioredis는 기본 `retryStrategy`가 무한 재시도이고 `ready`는 접속 성공 후에만 발생하므로,
     * 타임아웃 없이 대기하면 Redis가 부팅 시점에 응답하지 않을 때 `bootstrap()` 전체가 멈춘다
     * (HTTP 서버 미기동, health 엔드포인트 미응답, Eureka 미등록으로 이어짐).
     * `READY_TIMEOUT_MS` 내에 준비되지 않으면 예외를 던지지 않고 in-memory adapter로 폴백한다.
     */
    async connectToRedis(configService: ConfigService): Promise<void> {
        const host = getRequiredConfig(configService, ['REDIS_HOST', 'redis.host']);
        const port = Number(getOptionalConfig(configService, ['REDIS_PORT', 'redis.port']) ?? 6379);

        const pubClient = new Redis({ host, port });
        const subClient = pubClient.duplicate();
        pubClient.on('error', (err: unknown) => this.logger.error(`Redis pub client error: ${err instanceof Error ? err.message : String(err)}`));
        subClient.on('error', (err: unknown) => this.logger.error(`Redis sub client error: ${err instanceof Error ? err.message : String(err)}`));

        const ready = Promise.all([
            new Promise<void>((resolve, reject) => {
                pubClient.once('ready', resolve);
                pubClient.once('error', reject);
            }),
            new Promise<void>((resolve, reject) => {
                subClient.once('ready', resolve);
                subClient.once('error', reject);
            }),
        ]);

        const { promise: timeoutPromise, cancel: cancelTimeout } = timeout(READY_TIMEOUT_MS);
        try {
            await Promise.race([ready, timeoutPromise]);
            this.adapterConstructor = createAdapter(pubClient, subClient);
            this.logger.log(`Socket.IO Redis adapter connected (${host}:${port})`);
        } catch (err: unknown) {
            this.logger.warn(
                `Redis adapter 준비 실패 — in-memory adapter로 폴백 (단일 인스턴스에서만 브로드캐스트 정상): ${err instanceof Error ? err.message : String(err)}`,
            );
        } finally {
            cancelTimeout();
        }
    }

    createIOServer(port: number, options?: ServerOptions): Server {
        const server = super.createIOServer(port, options) as Server;
        if (this.adapterConstructor) {
            server.adapter(this.adapterConstructor);
        } else {
            this.logger.warn('Redis adapter가 초기화되지 않아 기본 in-memory adapter로 동작합니다 (단일 인스턴스에서만 브로드캐스트 정상)');
        }
        return server;
    }
}
