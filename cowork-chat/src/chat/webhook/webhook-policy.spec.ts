import {createHash} from 'node:crypto';
import {BadRequestException, ConflictException, UnauthorizedException} from '@nestjs/common';
import {SafePositiveIntPipe} from '../../common/pipe/safe-positive-int.pipe.js';
import {
	authenticateWebhook,
	isWebhookStateEvent,
	requireSameWebhookRequest,
	webhookDeliveryKey,
	webhookRequestHash,
} from './webhook-policy.js';

const secretHash = createHash('sha256').update('private-secret').digest('hex');

describe('incoming webhook policy', () => {
	it('uses the stored public mode even if the caller supplies a secret', () => {
		expect(() => {
			authenticateWebhook({isSecure: false, secretHash: null});
		}).not.toThrow();
		expect(() => {
			authenticateWebhook({isSecure: false, secretHash: null}, 'anything');
		}).not.toThrow();
	});

	it('requires the exact secret in secure mode', () => {
		expect(() => {
			authenticateWebhook({isSecure: true, secretHash}, 'private-secret');
		}).not.toThrow();
		for (const secret of [undefined, '', 'incorrect']) {
			expect(() => {
				authenticateWebhook({isSecure: true, secretHash}, secret);
			}).toThrow(UnauthorizedException);
		}

		expect(() => {
			authenticateWebhook({isSecure: true, secretHash: null}, 'private-secret');
		}).toThrow(UnauthorizedException);
	});

	it('keeps absent idempotency keys absent and hashes provided keys', () => {
		expect(webhookDeliveryKey()).toBeUndefined();
		expect(webhookDeliveryKey('delivery-1')).toBe(webhookDeliveryKey('delivery-1'));
		expect(webhookDeliveryKey('delivery-1')).not.toBe(webhookDeliveryKey('delivery-2'));
		for (const value of ['', ' leading', 'trailing ', 'a\nb', 'x'.repeat(201)]) {
			expect(() => webhookDeliveryKey(value)).toThrow(BadRequestException);
		}
	});

	it('rejects an idempotency key reused for different content', () => {
		const original = webhookRequestHash('first');
		expect(() => {
			requireSameWebhookRequest(original, webhookRequestHash('first'));
		}).not.toThrow();
		expect(() => {
			requireSameWebhookRequest(original, webhookRequestHash('second'));
		}).toThrow(ConflictException);
	});

	it('rejects incomplete secure state and malformed identity or versions', () => {
		const event = {
			schemaVersion: 2, eventType: 'WEBHOOK_STATE', webhookId: 7, executionId: '6cf6eda2-99a4-4f45-9bc9-14292c607df0', channelId: 8,
			name: 'build', avatarUrl: null, isSecure: true, secretHash, isActive: true,
			deleted: false, occurredAt: '2026-10-25T12:00:00.123456Z', snapshot: false,
		};
		expect(isWebhookStateEvent(event)).toBe(true);
		expect(isWebhookStateEvent({...event, secretHash: null})).toBe(false);
		expect(isWebhookStateEvent({...event, secretHash: null, deleted: true})).toBe(true);
		for (const webhookId of [1, 2 ** 52, Number.MAX_SAFE_INTEGER]) {
			expect(isWebhookStateEvent({...event, webhookId})).toBe(true);
			expect(new SafePositiveIntPipe().transform(String(webhookId))).toBe(webhookId);
		}

		expect(isWebhookStateEvent({...event, webhookId: Number.MAX_SAFE_INTEGER + 1})).toBe(false);
		expect(() => new SafePositiveIntPipe().transform(String(Number.MAX_SAFE_INTEGER + 1))).toThrow(BadRequestException);
		expect(isWebhookStateEvent({...event, occurredAt: 'yesterday'})).toBe(false);
	});
});
