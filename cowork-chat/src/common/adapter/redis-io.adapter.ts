import {
    INestApplicationContext,
    Injectable,
    Logger,
    OnApplicationShutdown,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { IoAdapter } from '@nestjs/platform-socket.io';
import { Server, ServerOptions } from 'socket.io';
import { createAdapter } from '@socket.io/redis-adapter';
import Redis from 'ioredis';
import { Counter, Gauge, register } from 'prom-client';
import { getOptionalConfig, getRequiredConfig } from '../config/config.util';
import { activeProfile } from '../config/config-server';

export type SocketIoAdapterState = 'CONNECTING' | 'READY' | 'DEGRADED' | 'IN_MEMORY' | 'STOPPED';

const ADAPTER_STATES: SocketIoAdapterState[] = ['CONNECTING', 'READY', 'DEGRADED', 'IN_MEMORY', 'STOPPED'];
const ADAPTER_MODE_KEY = 'CHAT_SOCKET_IO_ADAPTER';
const ERROR_WARN_INTERVAL_MS = 60_000;

type RedisClientName = 'pub' | 'sub';

/**
 * Redis adapter 상태 판정. `READY`는 adapter가 서버에 설치되고 pub/sub이 모두 `ready`일 때만이며,
 * 한 번 `READY`가 된 뒤 조건이 깨지면 최초 연결 대기(`CONNECTING`)와 구분해 `DEGRADED`로 본다.
 */
export function nextRedisAdapterState(input: {
    stopping: boolean;
    installed: boolean;
    pubReady: boolean;
    subReady: boolean;
    wasReady: boolean;
}): SocketIoAdapterState {
    if (input.stopping) {
        return 'STOPPED';
    }

    if (input.installed && input.pubReady && input.subReady) {
        return 'READY';
    }

    return input.wasReady ? 'DEGRADED' : 'CONNECTING';
}

/**
 * 명시적인 in-memory 모드는 단일 인스턴스 개발 전용이다. 운영 profile에서 요청되면 기동을 거부해
 * 다중 replica가 서로의 room 브로드캐스트를 받지 못하는 상태로 뜨지 않게 한다.
 */
export function resolveAdapterMode(mode: string | undefined, profile: string): 'redis' | 'in-memory' {
    const resolved = mode ?? 'redis';
    if (resolved !== 'redis' && resolved !== 'in-memory') {
        throw new Error(`${ADAPTER_MODE_KEY} must be 'redis' or 'in-memory': ${resolved}`);
    }

    if (resolved === 'in-memory' && profile === 'prod') {
        throw new Error(`${ADAPTER_MODE_KEY}=in-memory is not allowed in the prod profile`);
    }

    return resolved;
}

/**
 * Socket.IO Redis adapter의 pub/sub client를 소유하고 연결 상태를 추적한다.
 *
 * Socket.IO 기본(in-memory) adapter는 room 브로드캐스트를 단일 프로세스 범위로만 처리하므로, 여러 replica에서는
 * Redis pub/sub으로 인스턴스 간 브로드캐스트와 원격 room 해제를 동기화해야 한다.
 *
 * Redis 준비를 기다리지 않고 adapter를 서버 생성 시점에 바로 설치한다. ioredis는 연결 전 명령을 offline queue에
 * 보관했다가 연결되면 보내고, 재연결 때 기존 구독을 다시 등록하므로 Redis가 늦게 뜨거나 중간에 끊겨도 프로세스
 * 재시작 없이 같은 adapter가 복구된다. 대신 준비되지 않은 동안에는 readiness, Eureka 등록, WebSocket 가입을 막는다.
 */
@Injectable()
export class SocketIoRedisConnection implements OnApplicationShutdown {
    private readonly logger = new Logger(SocketIoRedisConnection.name);
    private readonly mode: 'redis' | 'in-memory';
    private readonly host?: string;
    private readonly port?: number;
    private pubClient?: Redis;
    private subClient?: Redis;
    private state: SocketIoAdapterState;
    private installed = false;
    private wasReady = false;
    private stopping = false;
    private degradedSince?: number;
    private lastError?: string;
    private lastErrorWarnAt = 0;
    private readonly readyWaiters: Array<() => void> = [];
    private readonly readinessListeners: Array<(ready: boolean) => void> = [];
    private readonly stateGauge: Gauge<'state'>;
    private readonly reconnects: Counter<'client'>;
    private readonly errors: Counter<'client'>;

    constructor(configService: ConfigService) {
        this.mode = resolveAdapterMode(getOptionalConfig(configService, ADAPTER_MODE_KEY), activeProfile());
        if (this.mode === 'redis') {
            this.host = getRequiredConfig(configService, ['REDIS_HOST', 'redis.host']);
            this.port = Number(getOptionalConfig(configService, ['REDIS_PORT', 'redis.port']) ?? 6379);
        }

        this.state = this.mode === 'in-memory' ? 'IN_MEMORY' : 'CONNECTING';

        this.stateGauge = this.metric('cowork_chat_socketio_adapter_state', () => new Gauge({
            name: 'cowork_chat_socketio_adapter_state',
            help: 'Socket.IO adapter state (1 for the current state).',
            labelNames: ['state'],
        }));
        const degradedSeconds: Gauge = this.metric('cowork_chat_socketio_adapter_degraded_seconds', () => new Gauge({
            name: 'cowork_chat_socketio_adapter_degraded_seconds',
            help: 'Seconds since the Socket.IO Redis adapter became degraded (0 when not degraded).',
            collect: () => {
                degradedSeconds.set(this.degradedSince ? (Date.now() - this.degradedSince) / 1000 : 0);
            },
        }));
        this.reconnects = this.metric('cowork_chat_socketio_redis_reconnects_total', () => new Counter({
            name: 'cowork_chat_socketio_redis_reconnects_total',
            help: 'Socket.IO Redis adapter client reconnect attempts.',
            labelNames: ['client'],
        }));
        this.errors = this.metric('cowork_chat_socketio_redis_errors_total', () => new Counter({
            name: 'cowork_chat_socketio_redis_errors_total',
            help: 'Socket.IO Redis adapter client errors.',
            labelNames: ['client'],
        }));
        this.recordState();
    }

    /**
     * Socket.IO 서버에 설치할 adapter를 만든다. in-memory 모드면 `undefined`를 반환해 기본 adapter를 쓰게 한다.
     */
    createAdapter(): ReturnType<typeof createAdapter> | undefined {
        if (this.mode === 'in-memory') {
            this.logger.warn(`Socket.IO adapter: in-memory (${ADAPTER_MODE_KEY}=in-memory) — 단일 인스턴스 전용, 다중 replica 불가`);
            return undefined;
        }

        // Pub client는 기본 재시도 한도를 유지한다. `fetchSockets()`는 pub client의 `PUBSUB NUMSUB`을 기다린 뒤에야
        // `requestsTimeout`을 걸기 때문에, 한도가 없으면 Redis 장애 동안 HTTP 요청과 Kafka consumer가 무기한 멈춘다.
        const pubClient = new Redis({ host: this.host, port: this.port });
        // 구독은 응답을 받은 뒤에만 재구독 대상으로 기록되므로, 연결 전 대기열의 SUBSCRIBE가 재시도 한도로 버려지면
        // 영구히 복구되지 않는다. sub client만 연결될 때까지 명령을 보관한다.
        const subClient = pubClient.duplicate({ maxRetriesPerRequest: null });
        this.pubClient = pubClient;
        this.subClient = subClient;
        this.watch('pub', pubClient);
        this.watch('sub', subClient);
        this.handlePublishRejection(pubClient);

        const adapter = createAdapter(pubClient, subClient);
        this.installed = true;
        this.logger.log(`Socket.IO adapter: redis (${this.host}:${this.port}) — 다중 replica 허용, 준비 전 WebSocket 차단`);
        this.updateState();
        return adapter;
    }

    isReady(): boolean {
        return this.state === 'READY' || this.state === 'IN_MEMORY';
    }

    whenReady(): Promise<void> {
        return this.isReady()
            ? Promise.resolve()
            : new Promise(resolve => {
                this.readyWaiters.push(resolve);
            });
    }

    /** `isReady()` 값이 바뀔 때마다 호출된다. 최초 준비 전 대기는 `whenReady()`를 쓴다. */
    onReadinessChange(listener: (ready: boolean) => void): void {
        this.readinessListeners.push(listener);
    }

    getStatus() {
        return {
            state: this.state,
            adapter: this.mode,
            pub: this.pubClient?.status ?? null,
            sub: this.subClient?.status ?? null,
            lastError: this.lastError ?? null,
        };
    }

    onApplicationShutdown(): void {
        this.stopping = true;
        this.updateState();
        // Disconnect는 ioredis의 재연결 timer를 취소하고 연결을 닫는다.
        this.pubClient?.disconnect();
        this.subClient?.disconnect();
    }

    private watch(name: RedisClientName, client: Redis): void {
        client.on('ready', () => {
            this.updateState();
        });
        client.on('close', () => {
            this.updateState();
        });
        client.on('reconnecting', () => {
            this.reconnects.inc({ client: name });
        });
        client.on('error', (error: unknown) => {
            this.recordError(name, error);
        });
    }

    /**
     * Adapter는 브로드캐스트·room 해제의 publish 결과를 버린다. 장애가 재시도 한도를 넘겨 publish가 거부되면
     * unhandled rejection으로 프로세스가 종료되므로, 거부를 오류로 기록하고 해당 발행은 유실된 것으로 본다.
     */
    private handlePublishRejection(pubClient: Redis): void {
        const publish = pubClient.publish.bind(pubClient) as (...args: unknown[]) => Promise<number>;
        pubClient.publish = ((...args: unknown[]) => {
            const result = publish(...args);
            result.catch((error: unknown) => {
                this.recordError('pub', error);
            });
            return result;
        });
    }

    private recordError(name: RedisClientName, error: unknown): void {
        this.errors.inc({ client: name });
        this.lastError = `${name}: ${error instanceof Error ? error.message : String(error)}`;
        // 재연결마다 error가 발생하므로 경고는 간격을 두고 남긴다.
        const now = Date.now();
        if (!(now - this.lastErrorWarnAt >= ERROR_WARN_INTERVAL_MS)) {
            return;
        }

        this.lastErrorWarnAt = now;
        this.logger.warn(`Socket.IO Redis ${this.lastError} (state=${this.state})`);
    }

    private updateState(): void {
        const next = nextRedisAdapterState({
            stopping: this.stopping,
            installed: this.installed,
            pubReady: this.pubClient?.status === 'ready',
            subReady: this.subClient?.status === 'ready',
            wasReady: this.wasReady,
        });
        if (next === this.state) {
            return;
        }

        const previous = this.state;
        const readyBefore = this.isReady();
        this.state = next;
        this.recordState();
        if (this.isReady() !== readyBefore) {
            this.readinessListeners.forEach((listener) => listener(!readyBefore));
        }
        if (next === 'READY') {
            this.wasReady = true;
            this.degradedSince = undefined;
            this.logger.log(`Socket.IO Redis adapter ready (${previous} → READY)`);
            this.resolveReadyWaiters();
        } else if (next === 'DEGRADED') {
            this.degradedSince = Date.now();
            this.logger.warn(`Socket.IO Redis adapter degraded: ${this.lastError ?? 'connection lost'}`);
        } else {
            this.logger.log(`Socket.IO Redis adapter ${previous} → ${next}`);
        }
    }

    private resolveReadyWaiters(): void {
        for (const resolve of this.readyWaiters.splice(0)) {
            resolve();
        }
    }

    private recordState(): void {
        for (const state of ADAPTER_STATES) {
            this.stateGauge.set({ state }, state === this.state ? 1 : 0);
        }
    }

    private metric<T>(name: string, create: () => T): T {
        return (register.getSingleMetric(name) as T | undefined) ?? create();
    }
}

export class RedisIoAdapter extends IoAdapter {
    constructor(
        app: INestApplicationContext,
        private readonly connection: SocketIoRedisConnection,
    ) {
        super(app);
    }

    createIOServer(port: number, options?: ServerOptions): Server {
        const server = super.createIOServer(port, options) as Server;
        const adapter = this.connection.createAdapter();
        if (adapter) {
            server.adapter(adapter);
        }

        return server;
    }
}
