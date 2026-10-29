import {
	CanActivate,
	ExecutionContext,
	HttpException,
	HttpStatus,
	Injectable as injectable,
} from '@nestjs/common';
import {Reflector} from '@nestjs/core';
import {ConfigService} from '@nestjs/config';
import {Request} from 'express';
import {RedisRateLimiter} from '../util/redis-rate-limiter.js';
import {requestContextUtil} from '../util/request-context.util.js';
import {getOptionalConfig} from '../config/config.util.js';
import {THROTTLE_OPTIONS_KEY, ThrottleOptions} from '../decorator/throttle.decorator.js';

const DEFAULT_THROTTLE_MESSAGE = '짧은 시간에 요청이 너무 많습니다. 잠시 후 다시 시도하십시오.';

@injectable()
export class ThrottleGuard implements CanActivate {
	constructor(
		private readonly reflector: Reflector,
		private readonly rateLimiter: RedisRateLimiter,
		private readonly configService: ConfigService,
	) {}

	private resolveNumberConfig(configKey: string, defaultValue: number): number {
		const raw = getOptionalConfig(this.configService, configKey);
		if (raw === undefined) {
			return defaultValue;
		}

		const parsed = Number(raw);
		return Number.isNaN(parsed) ? defaultValue : parsed;
	}

	async canActivate(context: ExecutionContext): Promise<boolean> {
		if (context.getType() !== 'http') {
			return true;
		}

		const options = this.reflector.getAllAndOverride<ThrottleOptions | undefined>(THROTTLE_OPTIONS_KEY, [
			context.getHandler(),
			context.getClass(),
		]);
		if (!options) {
			return true;
		}

		const request = context.switchToHttp().getRequest<Request>();
		const userId = requestContextUtil.getUserId(request.headers);

		const windowMs = this.resolveNumberConfig(options.windowMsConfigKey, options.defaultWindowMs);
		const maxRequests = this.resolveNumberConfig(options.maxRequestsConfigKey, options.defaultMaxRequests);

		const isAllowed = await this.rateLimiter.tryAcquire(`${options.key}:${userId}`, windowMs, maxRequests);
		if (!isAllowed) {
			throw new HttpException(options.message ?? DEFAULT_THROTTLE_MESSAGE, HttpStatus.TOO_MANY_REQUESTS);
		}

		return true;
	}
}
