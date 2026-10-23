import {
    Inject,
    Injectable,
    Logger,
    OnApplicationBootstrap,
} from '@nestjs/common';
import type Redis from 'ioredis';
import {REDIS_CLIENT} from '../constants';

const PULL_REQUEST_FILES_KEY_PATTERN = 'github:pr-files:*';
const SCAN_COUNT = 500;
const NO_EXPIRY = -1;

/**
 TODO: 일회성 정리 코드. 모든 환경에 한 번 배포된 뒤 이 파일과 `installation.module.ts`의 provider 등록을 함께 지운다.
 TTL을 두기 전에 만료 없이 저장된 PR 파일 목록 캐시를 지운다. 지운 PR은 다음 webhook에서 파일 목록을 다시 조회한다.
 TTL이 있는 키는 건드리지 않으므로 여러 레플리카가 동시에, 또는 재시작마다 다시 실행해도 결과가 같다.
 */
@Injectable()
export class LegacyPullRequestFilesCacheCleanup implements OnApplicationBootstrap {
    private readonly logger = new Logger(LegacyPullRequestFilesCacheCleanup.name);

    constructor(@Inject(REDIS_CLIENT) private readonly redis: Redis) {}

    onApplicationBootstrap(): void {
        void this.retireKeysWithoutExpiry().catch((error: unknown) => {
            this.logger.error(
                'Failed to retire legacy pull request files cache',
                error instanceof Error ? error.stack : String(error),
            );
        });
    }

    async retireKeysWithoutExpiry(): Promise<number> {
        let retired = 0;
        const stream = this.redis.scanStream({match: PULL_REQUEST_FILES_KEY_PATTERN, count: SCAN_COUNT});
        for await (const keys of stream as AsyncIterable<string[]>) {
            retired += await this.retireBatch(keys);
        }

        this.logger.log(`Retired legacy pull request files cache [keys=${retired}]`);
        return retired;
    }

    private async retireBatch(keys: string[]): Promise<number> {
        if (keys.length === 0) {
            return 0;
        }

        const pipeline = this.redis.pipeline();
        for (const key of keys) {
            pipeline.ttl(key);
        }

        const results = await pipeline.exec() ?? [];
        const legacyKeys = keys.filter((_, index) => results[index]?.[1] === NO_EXPIRY);
        if (legacyKeys.length > 0) {
            await this.redis.unlink(...legacyKeys);
        }

        return legacyKeys.length;
    }
}
