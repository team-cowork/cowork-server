import {
    Injectable,
    Logger,
    OnModuleDestroy,
    OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import Redis, {ChainableCommander, ClientContext, Result} from 'ioredis';
import {getOptionalConfig, getRequiredConfig} from '../../common/config/config.util';

// This.client와 그 pipeline()에만 캐스팅해서 붙이는 타입 — ioredis의 공유 RedisCommander를 전역으로
// 확장하면 defineCommand를 호출하지 않은 다른 Redis 클라이언트에도 이 메서드가 타입상 노출되어 버린다.
type WithIncrementIfPresentScript<Context extends ClientContext = {type: 'default'}> = {
    incrementIfPresentScript(field: string, appliedKey: string, appliedTtlSeconds: number, chunkIndex: number, ...keys: string[]): Result<number, Context>;
};
type UnreadCounterRedis = Redis & WithIncrementIfPresentScript;
type UnreadCounterPipeline = ChainableCommander & WithIncrementIfPresentScript<{type: 'pipeline'}>;

// ARGV[1]=field ARGV[2]=적용 기록 비트맵 키('' 이면 기록 없음) ARGV[3]=그 TTL ARGV[4]=청크 번호
// ARGV[5..]=유저별 unread:{userId} 해시 키 — 필드가 이미 존재하는 키에 대해서만 1 증가시킨다.
// 청크 하나가 스크립트 한 번이라 청크 단위로 원자적이므로, 적용 기록은 수신자가 아니라 청크 번호 비트로 남긴다.
// 비트가 이미 켜진 청크는 통째로 건너뛰어 같은 이벤트의 재처리가 (이벤트, 수신자)당 한 번만 반영되고,
// 기록은 수신자 수와 무관하게 이벤트당 수 바이트로 유지된다. 필드가 없어도 기록해야, 그 사이 캐시미스 폴백이
// 이 메시지를 포함한 값을 채운 뒤 재처리가 다시 더하지 않는다.
// 대상 키 개수가 호출마다 달라져 KEYS 대신 ARGV로 넘긴다(numberOfKeys 고정 0 — 단일 노드 Redis라
// 클러스터 슬롯 라우팅 대상이 아니라 안전하다).
const INCREMENT_IF_PRESENT_SCRIPT = `
local field = ARGV[1]
local applied = ARGV[2]
if applied ~= '' then
    local done = redis.call('SETBIT', applied, ARGV[4], 1)
    redis.call('EXPIRE', applied, ARGV[3])
    if done == 1 then
        return 0
    end
end
local incremented = 0
for i = 5, #ARGV do
    if redis.call('HEXISTS', ARGV[i], field) == 1 then
        redis.call('HINCRBY', ARGV[i], field, 1)
        incremented = incremented + 1
    end
end
return incremented
`;

const KEY_PREFIX = 'unread:';
// 수신자별 set을 쓰던 이전 `unread:applied:` 키와 타입이 달라 접두사를 분리한다.
const APPLIED_KEY_PREFIX = 'unread:applied-chunks:';
const TTL_SECONDS = 86_400;
const INCREMENT_CHUNK_SIZE = 100;

export type UnreadCacheResult = {
    hits: Map<number, number>;
    misses: number[];
};

/**
 채널별 안읽음 메시지 수를 캐싱하는 Redis 기반 cache-aside 카운터.

 `unread:{userId}` 해시에 `channelId` → count를 저장한다. Redis는 소모성 캐시일 뿐이며,
 정확한 값의 원천은 여전히 MongoDB 집계(`MessageRepository.countUnreadForChannels`/`countUnread`)다.
 캐시 미스나 Redis 오류는 항상 호출부가 MongoDB로 폴백하도록 신호를 보낸다(fail-open).

 증가(`incrementIfPresent`)는 필드가 이미 존재하는 유저에게만 적용된다 — 존재하지 않는 채널에
 무작정 1부터 증가시키면 실제 안읽음 수(과거 누적분)를 무시한 잘못된 값이 캐시에 고정되기 때문이다.
 필드가 없는 경우는 그대로 비워두고, 다음 조회 시 캐시미스 폴백이 정확한 값을 계산해 채운다.
 */
@Injectable()
export class UnreadCounterService implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(UnreadCounterService.name);
    private client!: UnreadCounterRedis;

    constructor(private readonly configService: ConfigService) {}

    onModuleInit(): void {
        const host = getRequiredConfig(this.configService, ['REDIS_HOST', 'redis.host']);
        const port = Number(getOptionalConfig(this.configService, ['REDIS_PORT', 'redis.port']) ?? 6379);

        this.client = new Redis({
            host,
            port,
            lazyConnect: true,
            enableOfflineQueue: false,
            maxRetriesPerRequest: 0,
        }) as UnreadCounterRedis;
        this.client.defineCommand('incrementIfPresentScript', {numberOfKeys: 0, lua: INCREMENT_IF_PRESENT_SCRIPT});
        this.client.on('error', (error: unknown) => {
            this.logger.error(`Redis client error: ${error instanceof Error ? error.message : String(error)}`);
        });
        void this.client.connect().catch((error: unknown) => {
            this.logger.warn(`Redis initial connection failed: ${error instanceof Error ? error.message : String(error)}`);
        });
    }

    onModuleDestroy(): void {
        this.client?.disconnect();
    }

    private key(userId: number): string {
        return `${KEY_PREFIX}${userId}`;
    }

    /**
     여러 채널의 캐시된 안읽음 수를 조회한다.

     @returns 캐시에 값이 있는 채널은 `hits`, 없는(또는 Redis 오류로 판단 불가한) 채널은 `misses`.
     Redis 자체가 오류를 던지면 `null`을 반환해 호출부가 전체를 미스로 간주하고 폴백하게 한다.
     */
    async getMany(userId: number, channelIds: number[]): Promise<UnreadCacheResult | null> {
        if (channelIds.length === 0) {
            return {hits: new Map(), misses: []};
        }

        try {
            const values = await this.client.hmget(this.key(userId), ...channelIds.map(String));
            const hits = new Map<number, number>();
            const misses: number[] = [];
            for (const [i, channelId] of channelIds.entries()) {
                const raw = values[i];
                if (raw === null || raw === undefined) {
                    misses.push(channelId);
                } else {
                    hits.set(channelId, Number(raw));
                }
            }

            return {hits, misses};
        } catch (error) {
            this.logger.warn(`Redis getMany failed, treating as full cache miss [userId=${userId}]`, error);
            return null;
        }
    }

    /**
     여러 채널의 안읽음 수를 캐시에 채운다 (캐시미스 폴백 계산 결과를 기록할 때 사용).
     */
    async setMany(userId: number, values: Map<number, number>): Promise<void> {
        if (values.size === 0) {
            return;
        }

        try {
            const args: string[] = [];
            for (const [channelId, count] of values) {
                args.push(String(channelId), String(count));
            }

            const key = this.key(userId);
            await this.client.pipeline().hset(key, ...args).expire(key, TTL_SECONDS).exec();
        } catch (error) {
            this.logger.warn(`Redis setMany failed [userId=${userId}]`, error);
        }
    }

    /**
     단일 채널의 안읽음 수를 정확한 값으로 설정한다 (읽음 처리 시 사용).
     */
    async set(channelId: number, userId: number, count: number): Promise<void> {
        try {
            const key = this.key(userId);
            await this.client.pipeline().hset(key, String(channelId), String(count)).expire(key, TTL_SECONDS).exec();
        } catch (error) {
            this.logger.warn(`Redis set failed [channelId=${channelId}, userId=${userId}]`, error);
        }
    }

    /**
     새 메시지 발생 시 해당 채널 필드가 이미 캐시에 존재하는 유저에 대해서만 안읽음 수를 1 증가시킨다.
     `event`(메시지 ID와 생성 시각)를 주면 `unread:applied-chunks:{id}` 비트맵으로 (이벤트, 수신자)당 한 번만
     반영해, outbox 재처리가 같은 메시지로 안읽음 수를 다시 올리지 않는다. 비트맵은 캐시와 같은 TTL 동안 유지된다.
     첫 처리는 생성 이후이므로 생성 후 TTL이 지나기 전에는 이전 처리의 기록이 남아 있다. 그 뒤에는 기록이 사라져
     중복을 막을 수 없으므로 증가하지 않고, 해당 채널 값은 다음 캐시 갱신에서 DB 값으로 바로잡힌다.
     필드가 없는(캐시 미스 상태인) 유저는 건드리지 않는다 — 다음 조회의 캐시미스 폴백이 처리한다.
     유저별 "존재하면 증가" 조건분기를 Lua 스크립트로 옮기되, 유저 간 원자성이 필요 없는 연산이라
     `INCREMENT_CHUNK_SIZE` 단위로 잘라 파이프라인에 담는다 — 채널 멤버가 수천 명이어도 스크립트
     한 번의 실행 시간이 짧게 유지되어 그 사이 Redis 인스턴스가 길게 블로킹되지 않는다.
     재처리 때 같은 청크 번호가 같은 수신자를 가리키도록 유저 ID를 정렬해 자른다.
     */
    async incrementIfPresent(channelId: number, userIds: number[], event?: {id: string; createdAt: Date}): Promise<void> {
        if (userIds.length === 0) {
            return;
        }

        if (event && Date.now() - event.createdAt.getTime() >= TTL_SECONDS * 1000) {
            this.logger.warn(`Skipping unread increment for event older than applied-record TTL [channelId=${channelId}, eventId=${event.id}]`);
            return;
        }

        try {
            const field = String(channelId);
            const appliedKey = event === undefined ? '' : `${APPLIED_KEY_PREFIX}${event.id}`;
            // Ponytail: 재처리 사이 멤버가 바뀌면 청크 경계가 밀려 일부 수신자가 한 번 덜/더 반영될 수 있다. 캐시는 TTL로 DB 값에 수렴한다.
            const keys = userIds.toSorted((a, b) => a - b).map(userId => this.key(userId));
            const pipeline = this.client.pipeline() as UnreadCounterPipeline;
            for (let i = 0; i < keys.length; i += INCREMENT_CHUNK_SIZE) {
                pipeline.incrementIfPresentScript(field, appliedKey, TTL_SECONDS, i / INCREMENT_CHUNK_SIZE, ...keys.slice(i, i + INCREMENT_CHUNK_SIZE));
            }

            await pipeline.exec();
        } catch (error) {
            this.logger.warn(`Redis incrementIfPresent failed [channelId=${channelId}]`, error);
        }
    }
}
