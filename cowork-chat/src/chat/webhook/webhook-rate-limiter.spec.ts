import {ConfigService} from '@nestjs/config';
import {ServiceUnavailableException} from '@nestjs/common';
import {WebhookRateLimiter, WebhookRateLimitExceeded} from './webhook-rate-limiter';

describe('WebhookRateLimiter', () => {
    const redis = {tryAcquireWebhook: jest.fn()};
    const limiter = new WebhookRateLimiter(redis as never, new ConfigService());

    beforeEach(() => {
        jest.resetAllMocks();
        redis.tryAcquireWebhook.mockResolvedValue(0);
    });

    it('changes limits without changing counters or the retention window', async () => {
        await limiter.acquire(7, false, '192.0.2.1');
        await limiter.acquire(7, true, '192.0.2.1');
        const [publicCall, secureCall] = redis.tryAcquireWebhook.mock.calls as Array<[string, string, number, number, number]>;
        expect(publicCall.slice(0, 3)).toEqual(secureCall.slice(0, 3));
        expect(publicCall.slice(2)).toEqual([60_000, 10, 10]);
        expect(secureCall.slice(2)).toEqual([60_000, 60, 60]);
    });

    it('separates webhook and IP scopes', async () => {
        await limiter.acquire(7, false, '192.0.2.1');
        await limiter.acquire(7, false, '192.0.2.2');
        await limiter.acquire(8, false, '192.0.2.1');
        const [first, second, third] = redis.tryAcquireWebhook.mock.calls as Array<[string, string, number, number, number]>;
        expect(first[0]).toBe(second[0]);
        expect(first[1]).not.toBe(second[1]);
        expect(first[0]).not.toBe(third[0]);
    });

    it('provides rounded-up retry time', async () => {
        redis.tryAcquireWebhook.mockResolvedValue(1501);
        await expect(limiter.acquire(7, false, '192.0.2.1')).rejects.toMatchObject({retryAfterSeconds: 2});
        await expect(limiter.acquire(7, false, '192.0.2.1')).rejects.toBeInstanceOf(WebhookRateLimitExceeded);
    });

    it('fails closed when IP provenance or Redis availability cannot be established', async () => {
        await expect(limiter.acquire(7, false)).rejects.toBeInstanceOf(ServiceUnavailableException);
        await expect(limiter.acquire(7, false, 'client-supplied,forwarded')).rejects.toBeInstanceOf(ServiceUnavailableException);
        expect(redis.tryAcquireWebhook).not.toHaveBeenCalled();
        redis.tryAcquireWebhook.mockRejectedValue(new ServiceUnavailableException());
        await expect(limiter.acquire(7, true, '192.0.2.1')).rejects.toBeInstanceOf(ServiceUnavailableException);
    });
});
