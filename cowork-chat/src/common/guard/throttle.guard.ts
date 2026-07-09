import { CanActivate, ExecutionContext, HttpException, HttpStatus, Injectable } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { ConfigService } from '@nestjs/config';
import { Request } from 'express';
import { RedisRateLimiter } from '../util/redis-rate-limiter';
import { RequestContextUtil } from '../util/request-context.util';
import { getOptionalConfig } from '../config/config.util';
import { THROTTLE_OPTIONS_KEY, ThrottleOptions } from '../decorator/throttle.decorator';

const DEFAULT_THROTTLE_MESSAGE = '짧은 시간에 요청이 너무 많습니다. 잠시 후 다시 시도하십시오.';

@Injectable()
export class ThrottleGuard implements CanActivate {
    constructor(
        private readonly reflector: Reflector,
        private readonly rateLimiter: RedisRateLimiter,
        private readonly configService: ConfigService,
    ) {}

    async canActivate(context: ExecutionContext): Promise<boolean> {
        const options = this.reflector.getAllAndOverride<ThrottleOptions>(THROTTLE_OPTIONS_KEY, [
            context.getHandler(),
            context.getClass(),
        ]);
        if (!options) return true;

        const request = context.switchToHttp().getRequest<Request>();
        const userId = RequestContextUtil.getUserId(request.headers);

        const windowMs = Number(getOptionalConfig(this.configService, options.windowMsConfigKey) ?? options.defaultWindowMs);
        const maxRequests = Number(getOptionalConfig(this.configService, options.maxRequestsConfigKey) ?? options.defaultMaxRequests);

        const allowed = await this.rateLimiter.tryAcquire(`${options.key}:${userId}`, windowMs, maxRequests);
        if (!allowed) {
            throw new HttpException(options.message ?? DEFAULT_THROTTLE_MESSAGE, HttpStatus.TOO_MANY_REQUESTS);
        }
        return true;
    }
}
