import {
	HttpException,
	HttpStatus,
	Injectable as injectable,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {getOptionalConfig} from '../../common/config/config.util.js';
import {RedisRateLimiter} from '../../common/util/redis-rate-limiter.js';

export class WebhookRateLimitExceeded extends HttpException {
	constructor(readonly retryAfterSeconds: number) {
		super({message: '웹훅 요청이 너무 많습니다. 잠시 후 다시 시도하십시오.', retryAfterSeconds}, HttpStatus.TOO_MANY_REQUESTS);
	}
}

@injectable()
export class WebhookRateLimiter {
	private readonly windowMs: number;
	private readonly publicLimit: number;
	private readonly secureLimit: number;

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
	}

	async acquire(webhookId: number, isSecure: boolean): Promise<void> {
		const retryAfterMs = await this.redis.tryAcquireWebhook(
			`chat:webhook:{${webhookId}}`,
			this.windowMs,
			isSecure ? this.secureLimit : this.publicLimit,
		);
		if (retryAfterMs > 0) {
			throw new WebhookRateLimitExceeded(Math.ceil(retryAfterMs / 1000));
		}
	}
}
