import {Buffer} from 'node:buffer';
import {createHash, timingSafeEqual} from 'node:crypto';
import {BadRequestException, ConflictException, UnauthorizedException} from '@nestjs/common';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {type Nullable} from '../../common/type/nullable.js';

export const WEBHOOK_CONTENT_MAX_LENGTH = 2000;
const WEBHOOK_EXECUTION_ID_PATTERN = /^[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/v;

export function normalizeWebhookExecutionId(value: string): string {
	const canonical = value.toLowerCase();
	if (!WEBHOOK_EXECUTION_ID_PATTERN.test(canonical)) {
		throw new BadRequestException('웹훅 실행 ID가 올바르지 않습니다.');
	}

	return canonical;
}

export type WebhookState = {
	webhookId: number;
	executionId: string;
	channelId: number;
	name: string;
	avatarUrl: Nullable<string>;
	isSecure: boolean;
	secretHash: Nullable<string>;
	isActive: boolean;
	deleted: boolean;
};

type WebhookStateEvent = WebhookState & {
	schemaVersion: 2;
	eventType: 'WEBHOOK_STATE';
	occurredAt: string;
	snapshot: boolean;
};

export function isWebhookStateEvent(payload: unknown): payload is WebhookStateEvent {
	if (typeof payload !== 'object' || payload === null) {
		return false;
	}

	const event = payload as Partial<WebhookStateEvent>;
	return event.schemaVersion === 2
		&& event.eventType === 'WEBHOOK_STATE'
		&& typeof event.executionId === 'string' && WEBHOOK_EXECUTION_ID_PATTERN.test(event.executionId)
		&& isSafePositiveInteger(event.webhookId)
		&& isSafePositiveInteger(event.channelId)
		&& typeof event.name === 'string' && event.name.length > 0 && event.name.length <= 100
		&& (event.avatarUrl === null || typeof event.avatarUrl === 'string')
		&& hasValidWebhookSecret(event)
		&& typeof event.isActive === 'boolean'
		&& typeof event.deleted === 'boolean'
		&& typeof event.snapshot === 'boolean'
		&& parseEventTime(event.occurredAt) !== null;
}

export function authenticateWebhook(webhook: Pick<WebhookState, 'isSecure' | 'secretHash'>, secret?: string): void {
	if (!webhook.isSecure) {
		return;
	}

	if (secret === undefined || secret.length === 0 || secret.length > 512 || webhook.secretHash === null || !/^[\da-f]{64}$/v.test(webhook.secretHash)
		|| !timingSafeEqual(createHash('sha256').update(secret).digest(), Buffer.from(webhook.secretHash, 'hex'))) {
		throw new UnauthorizedException('웹훅 인증 정보가 올바르지 않습니다.');
	}
}

export function webhookDeliveryKey(value?: string): string | undefined {
	if (value === undefined) {
		return undefined;
	}

	if (value.length === 0 || value.length > 200 || value.trim() !== value || /\p{Control}/v.test(value)) {
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

/**
보안 웹훅은 삭제되지 않았다면 SHA-256 hex 비밀값 해시를 가져야 한다.
*/
function hasValidWebhookSecret(event: Partial<WebhookStateEvent>): boolean {
	return typeof event.isSecure === 'boolean'
		&& (event.secretHash === null || (typeof event.secretHash === 'string' && /^[\da-f]{64}$/v.test(event.secretHash)))
		&& (!event.isSecure || event.deleted === true || event.secretHash !== null);
}
