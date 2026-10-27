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
type WithUnreadCounterScripts<Context extends ClientContext = {type: 'default'}> = {
    incrementIfPresentScript(field: string, appliedKey: string, appliedTtlSeconds: number, chunkIndex: number, ...keys: string[]): Result<number, Context>;
    setIfNotStaleScript(key: string, field: string, cursorField: string, cursor: string, count: string, ttlSeconds: number, replaceSameCursorFlag: string): Result<number, Context>;
};
type UnreadCounterRedis = Redis & WithUnreadCounterScripts;
type UnreadCounterPipeline = ChainableCommander & WithUnreadCounterScripts<{type: 'pipeline'}>;

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

// KEYS[1]=unread:{userId} ARGV[1]=channelId 필드 ARGV[2]=그 읽음 위치 필드 ARGV[3]=이번 값의 읽음 위치(hex, 없으면 '')
// ARGV[4]=안읽음 수 ARGV[5]=TTL ARGV[6]=같은 읽음 위치면 덮어쓸지('1'|'0')
// 저장된 읽음 위치보다 오래된 위치로 계산한 값은 버린다. ObjectId hex는 길이가 같은 소문자라 문자열 비교가
// MongoDB `$max`의 ObjectId 비교와 순서가 같고, ''(읽음 위치 없음)는 어떤 위치보다도 앞선다.
// 읽음 위치가 기록되지 않은 이전 형식의 값은 읽음 처리는 덮어쓰고, 캐시미스 채움은 이미 값이 있으면 건드리지 않는다.
const SET_IF_NOT_STALE_SCRIPT = `
local stored = redis.call('HGET', KEYS[1], ARGV[2])
if stored then
    if ARGV[3] < stored or (ARGV[3] == stored and ARGV[6] ~= '1') then
        return 0
    end
elseif ARGV[6] ~= '1' and redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then
    return 0
end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[4], ARGV[2], ARGV[3])
redis.call('EXPIRE', KEYS[1], ARGV[5])
return 1
`;

const KEY_PREFIX = 'unread:';
const CURSOR_FIELD_SUFFIX = ':cursor';
// 수신자별 set을 쓰던 이전 `unread:applied:` 키와 타입이 달라 접두사를 분리한다.
const APPLIED_KEY_PREFIX = 'unread:applied-chunks:';
const TTL_SECONDS = 86_400;
const INCREMENT_CHUNK_SIZE = 100;

export type UnreadCacheEntry = {
    count: number;
    // 이 값을 계산한 기준 읽음 위치(ObjectId hex). 읽음 기록이 없으면 null.
    readCursor: string | null;
};

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

 정확한 값을 쓰는 경로(`set`/`setMany`)는 그 값을 계산한 읽음 위치를 `{channelId}:cursor` 필드에 함께 남기고,
 이미 더 최신 읽음 위치로 계산된 값이 있으면 쓰지 않는다. 다른 기기의 늦은 읽음 요청이나 읽음 처리보다 늦게
 끝난 캐시미스 채움이 최신 읽음 처리 결과를 덮지 못하게 하기 위해서다.
 Ponytail: 같은 읽음 위치에서 새 메시지 증가와 정확한 값 쓰기가 교차하는 경우(계산 직후 도착한 메시지의 증가를
 계산 결과가 덮거나, 계산에 이미 포함된 메시지를 증가가 다시 더하는 경우)는 구분하지 않는다. 그 어긋남은 다음 읽음
 처리나 키 만료까지 남는다.
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
        this.client.defineCommand('setIfNotStaleScript', {numberOfKeys: 1, lua: SET_IF_NOT_STALE_SCRIPT});
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
     캐시미스 폴백으로 계산한 여러 채널의 안읽음 수를 채운다.
     그 사이 같거나 더 최신 읽음 위치로 값이 기록된 채널은 건너뛴다.
     */
    async setMany(userId: number, values: Map<number, UnreadCacheEntry>): Promise<void> {
        if (values.size === 0) {
            return;
        }

        try {
            const key = this.key(userId);
            const pipeline = this.client.pipeline() as UnreadCounterPipeline;
            for (const [channelId, entry] of values) {
                this.queueSetIfNotStale(pipeline, key, channelId, entry, false);
            }

            await pipeline.exec();
        } catch (error) {
            this.logger.warn(`Redis setMany failed [userId=${userId}]`, error);
        }
    }

    /**
     읽음 처리로 계산한 단일 채널의 안읽음 수를 기록한다. 같은 읽음 위치의 값은 덮어쓴다.

     @returns 기록했으면 `true`, 이미 더 최신 읽음 위치의 값이 있어 버렸으면 `false`, Redis 오류면 `null`
     */
    async set(channelId: number, userId: number, entry: UnreadCacheEntry): Promise<boolean | null> {
        try {
            const pipeline = this.client.pipeline() as UnreadCounterPipeline;
            this.queueSetIfNotStale(pipeline, this.key(userId), channelId, entry, true);
            const [[error, applied]] = (await pipeline.exec())!;
            if (error) {
                throw error;
            }

            return applied === 1;
        } catch (error) {
            this.logger.warn(`Redis set failed [channelId=${channelId}, userId=${userId}]`, error);
            return null;
        }
    }

    private queueSetIfNotStale(
        pipeline: UnreadCounterPipeline,
        key: string,
        channelId: number,
        entry: UnreadCacheEntry,
        shouldReplaceSameCursor: boolean,
    ): void {
        const field = String(channelId);
        pipeline.setIfNotStaleScript(
            key,
            field,
            `${field}${CURSOR_FIELD_SUFFIX}`,
            entry.readCursor ?? '',
            String(entry.count),
            TTL_SECONDS,
            shouldReplaceSameCursor ? '1' : '0',
        );
    }

    /**
     새 메시지 발생 시 해당 채널 필드가 이미 캐시에 존재하는 유저에 대해서만 안읽음 수를 1 증가시킨다.
     `eventId`(메시지 ID)를 주면 `unread:applied-chunks:{eventId}` 비트맵으로 (eventId, 수신자)당 한 번만 반영해,
     outbox 재처리가 같은 메시지로 안읽음 수를 다시 올리지 않는다. 비트맵 TTL은 처리할 때마다 캐시와 같은 TTL로
     다시 늘어나므로, 기록이 사라진 뒤 재처리되는 경우는 처리 사이 간격이 TTL을 넘는 장애뿐이다.
     필드가 없는(캐시 미스 상태인) 유저는 건드리지 않는다 — 다음 조회의 캐시미스 폴백이 처리한다.
     유저별 "존재하면 증가" 조건분기를 Lua 스크립트로 옮기되, 유저 간 원자성이 필요 없는 연산이라
     `INCREMENT_CHUNK_SIZE` 단위로 잘라 파이프라인에 담는다 — 채널 멤버가 수천 명이어도 스크립트
     한 번의 실행 시간이 짧게 유지되어 그 사이 Redis 인스턴스가 길게 블로킹되지 않는다.
     재처리 때 같은 청크 번호가 같은 수신자를 가리키도록 유저 ID를 정렬해 자른다.
     */
    async incrementIfPresent(channelId: number, userIds: number[], eventId?: string): Promise<void> {
        if (userIds.length === 0) {
            return;
        }

        try {
            const field = String(channelId);
            const appliedKey = eventId === undefined ? '' : `${APPLIED_KEY_PREFIX}${eventId}`;
            // Ponytail: 재처리 사이 멤버가 바뀌면 청크 경계가 밀려 일부 수신자가 한 번 덜/더 반영될 수 있다.
            // set/setMany가 키 TTL을 계속 늘리므로 어긋난 값은 그 채널을 읽어 다시 설정하거나 키가 만료될 때까지 남는다.
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
