import {createHash, timingSafeEqual} from 'node:crypto';
import {BadRequestException, ConflictException, UnauthorizedException} from '@nestjs/common';
import {parseEventTime} from '../../common/util/event-time.util';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util';

export const WEBHOOK_CONTENT_MAX_LENGTH = 2000;

export type WebhookState = {
    webhookId: number;
    channelId: number;
    name: string;
    avatarUrl: string | null;
    isSecure: boolean;
    secretHash: string | null;
    isActive: boolean;
    deleted: boolean;
};

type WebhookStateEvent = WebhookState & {
    schemaVersion: 1;
    eventType: 'WEBHOOK_STATE';
    occurredAt: string;
    snapshot: boolean;
};

export function isWebhookStateEvent(payload: unknown): payload is WebhookStateEvent {
    if (typeof payload !== 'object' || payload === null) {
        return false;
    }

    const event = payload as Partial<WebhookStateEvent>;
    return event.schemaVersion === 1
        && event.eventType === 'WEBHOOK_STATE'
        && isSafePositiveInteger(event.webhookId)
        && isSafePositiveInteger(event.channelId)
        && typeof event.name === 'string' && event.name.length > 0 && event.name.length <= 100
        && (event.avatarUrl === null || typeof event.avatarUrl === 'string')
        && typeof event.isSecure === 'boolean'
        && (event.secretHash === null || (typeof event.secretHash === 'string' && /^[\da-f]{64}$/u.test(event.secretHash)))
        && (!event.isSecure || event.deleted === true || event.secretHash !== null)
        && typeof event.isActive === 'boolean'
        && typeof event.deleted === 'boolean'
        && typeof event.snapshot === 'boolean'
        && parseEventTime(event.occurredAt) !== null;
}

export function authenticateWebhook(webhook: Pick<WebhookState, 'isSecure' | 'secretHash'>, secret?: string): void {
    if (!webhook.isSecure) {
        return;
    }

    if (secret === undefined || secret.length === 0 || secret.length > 512 || webhook.secretHash === null || !/^[\da-f]{64}$/u.test(webhook.secretHash)
        || !timingSafeEqual(createHash('sha256').update(secret).digest(), Buffer.from(webhook.secretHash, 'hex'))) {
        throw new UnauthorizedException('웹훅 인증 정보가 올바르지 않습니다.');
    }
}

export function webhookDeliveryKey(value?: string): string | undefined {
    if (value === undefined) {
        return undefined;
    }

    if (value.length === 0 || value.length > 200 || value.trim() !== value || /\p{Control}/u.test(value)) {
        throw new BadRequestException('Idempotency-Key가 올바르지 않습니다.');
    }

    return createHash('sha256').update(value).digest('hex');
}

export function webhookRequestHash(content: string): string {
    return createHash('sha256').update(JSON.stringify({content})).digest('hex');
}

export function requireSameWebhookRequest(storedHash: string | undefined, requestHash: string): void {
    if (storedHash !== requestHash) {
        throw new ConflictException('같은 Idempotency-Key로 다른 내용을 전송할 수 없습니다.');
    }
}
