import {applyDecorators, SetMetadata, UseGuards} from '@nestjs/common';
import {ThrottleGuard} from '../guard/throttle.guard';
import {THROTTLE_OPTIONS_KEY, type ThrottleOptions} from './throttle.options';

/**
 사용자별 요청 빈도를 제한한다. `RedisRateLimiter`의 슬라이딩 윈도우 방식을 그대로 사용해
 다중 인스턴스 환경에서도 한도가 공유되며, Redis 장애 시 fail-open으로 동작한다.
 */
export const Throttle = (options: ThrottleOptions) =>
    applyDecorators(SetMetadata(THROTTLE_OPTIONS_KEY, options), UseGuards(ThrottleGuard));
