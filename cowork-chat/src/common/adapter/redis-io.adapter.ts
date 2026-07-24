import { INestApplicationContext, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { IoAdapter } from '@nestjs/platform-socket.io';
import { Server, ServerOptions } from 'socket.io';
import { createAdapter } from '@socket.io/redis-adapter';
import Redis from 'ioredis';
import { getOptionalConfig, getRequiredConfig } from '../config/config.util';

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
     */
    async connectToRedis(configService: ConfigService): Promise<void> {
        const host = getRequiredConfig(configService, ['REDIS_HOST', 'redis.host']);
        const port = Number(getOptionalConfig(configService, ['REDIS_PORT', 'redis.port']) ?? 6379);

        const pubClient = new Redis({ host, port });
        const subClient = pubClient.duplicate();
        pubClient.on('error', (err: unknown) => this.logger.error(`Redis pub client error: ${err instanceof Error ? err.message : String(err)}`));
        subClient.on('error', (err: unknown) => this.logger.error(`Redis sub client error: ${err instanceof Error ? err.message : String(err)}`));

        await Promise.all([
            new Promise<void>((resolve) => pubClient.once('ready', resolve)),
            new Promise<void>((resolve) => subClient.once('ready', resolve)),
        ]);

        this.adapterConstructor = createAdapter(pubClient, subClient);
        this.logger.log(`Socket.IO Redis adapter connected (${host}:${port})`);
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
