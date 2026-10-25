import {ConfigService} from '@nestjs/config';
import {ServiceUnavailableException} from '@nestjs/common';
import {WebhookRateLimiter, WebhookRateLimitExceeded} from './webhook-rate-limiter';

describe('WebhookRateLimiter security policy', () => {
    const redis = {tryAcquireWebhook: jest.fn()};
    const limiter = new WebhookRateLimiter(redis as never, new ConfigService());

    beforeEach(() => {
        jest.resetAllMocks();
        const usage = new Map<string, number>();
        redis.tryAcquireWebhook.mockImplementation(async (key: string, windowMs: number, limit: number): Promise<number> => {
            const consumed = usage.get(key) ?? 0;
            if (consumed >= limit) {
                return windowMs;
            }

            usage.set(key, consumed + 1);
            return 0;
        });
    });

    it('allows ten new public messages before rejecting further messages for the window', async () => {
        // Given
        await Promise.all(Array.from({length: 10}, async () => limiter.acquire(7, false)));

        // When / Then
        await expect(limiter.acquire(7, false)).rejects.toMatchObject({retryAfterSeconds: 60});
    });

    it('allows sixty authenticated secure messages before rejecting further messages', async () => {
        // Given
        await Promise.all(Array.from({length: 60}, async () => limiter.acquire(7, true)));

        // When / Then
        await expect(limiter.acquire(7, true)).rejects.toBeInstanceOf(WebhookRateLimitExceeded);
    });

    it('does not charge one webhook for messages sent to another webhook', async () => {
        // Given
        await Promise.all(Array.from({length: 10}, async () => limiter.acquire(7, false)));

        // When / Then
        await expect(limiter.acquire(7, false)).rejects.toBeInstanceOf(WebhookRateLimitExceeded);
        await expect(limiter.acquire(8, false)).resolves.toBeUndefined();
    });

    it('keeps the already consumed quota when switching modes', async () => {
        // Given
        await Promise.all(Array.from({length: 10}, async () => limiter.acquire(7, false)));

        // When
        await Promise.all(Array.from({length: 50}, async () => limiter.acquire(7, true)));

        // Then
        await expect(limiter.acquire(7, true)).rejects.toBeInstanceOf(WebhookRateLimitExceeded);
        await expect(limiter.acquire(7, false)).rejects.toBeInstanceOf(WebhookRateLimitExceeded);
    });

    it('does not tell callers to retry before the quota is available', async () => {
        // Given
        redis.tryAcquireWebhook.mockResolvedValue(1501);

        // When / Then
        await expect(limiter.acquire(7, false)).rejects.toMatchObject({retryAfterSeconds: 2});
    });

    it('rejects new messages when the shared quota cannot be verified', async () => {
        // Given
        redis.tryAcquireWebhook.mockRejectedValue(new ServiceUnavailableException());

        // When / Then
        await expect(limiter.acquire(7, true)).rejects.toBeInstanceOf(ServiceUnavailableException);
    });
});
