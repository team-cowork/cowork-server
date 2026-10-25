import {isIP} from 'node:net';
import {createHash} from 'node:crypto';
import {
    HttpException,
    HttpStatus,
    Injectable,
    ServiceUnavailableException,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {getOptionalConfig} from '../../common/config/config.util';
import {RedisRateLimiter} from '../../common/util/redis-rate-limiter';

export class WebhookRateLimitExceeded extends HttpException {
    constructor(readonly retryAfterSeconds: number) {
        super({message: '웹훅 요청이 너무 많습니다. 잠시 후 다시 시도하십시오.', retryAfterSeconds}, HttpStatus.TOO_MANY_REQUESTS);
    }
}

export function requireWebhookClientIp(clientIp?: string): string {
    if (clientIp === undefined || isIP(clientIp) === 0) {
        throw new ServiceUnavailableException('웹훅 요청 정보를 확인할 수 없습니다.');
    }

    return clientIp;
}

@Injectable()
export class WebhookRateLimiter {
    private readonly windowMs: number;
    private readonly publicLimit: number;
    private readonly secureLimit: number;
    private readonly publicIpLimit: number;
    private readonly secureIpLimit: number;

    constructor(private readonly redis: RedisRateLimiter, config: ConfigService) {
        const setting = (key: string, fallback: number): number => {
            const value = Number(getOptionalConfig(config, `webhook.rate-limit.${key}`) ?? fallback);
            if (!Number.isSafeInteger(value) || value <= 0) {
                throw new Error(`Invalid webhook rate limit: ${key}`);
            }

            return value;
        };

        this.windowMs = setting('window-ms', 60_000);
        this.publicLimit = setting('public-limit', 10);
        this.secureLimit = setting('secure-limit', 60);
        this.publicIpLimit = setting('public-ip-limit', 10);
        this.secureIpLimit = setting('secure-ip-limit', 60);
    }

    async acquire(webhookId: number, isSecure: boolean, clientIp?: string): Promise<void> {
        const ipHash = createHash('sha256').update(requireWebhookClientIp(clientIp).toLowerCase()).digest('hex');
        const retryAfterMs = await this.redis.tryAcquireWebhook(
            `chat:webhook:{${webhookId}}`,
            ipHash,
            this.windowMs,
            isSecure ? this.secureLimit : this.publicLimit,
            isSecure ? this.secureIpLimit : this.publicIpLimit,
        );
        if (retryAfterMs > 0) {
            throw new WebhookRateLimitExceeded(Math.ceil(retryAfterMs / 1000));
        }
    }
}
