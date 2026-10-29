import {createHash} from 'node:crypto';
import {
	BadRequestException,
	UnauthorizedException,
	ServiceUnavailableException,
	ForbiddenException,
	NotFoundException,
} from '@nestjs/common';
import {Types} from 'mongoose';
import {WebhookRateLimitExceeded} from './webhook-rate-limiter.js';
import {WebhookService} from './webhook.service.js';

const executionId = '6cf6eda2-99a4-4f45-9bc9-14292c607df0';
const state = {
	webhookId: 7, executionId, channelId: 8, name: 'build', avatarUrl: null, isSecure: false,
	secretHash: createHash('sha256').update('secret').digest('hex'), isActive: true, deleted: false,
};

describe('WebhookService.execute', () => {
	const webhooks = {findByExecutionId: jest.fn()};
	const channels = {findById: jest.fn()};
	const readiness = {areReady: jest.fn()};
	const limiter = {acquire: jest.fn()};
	const messages = {findDelivery: jest.fn(), save: jest.fn()};
	const chat = {emitToReadableChannelUsers: jest.fn()};
	const readAccess = {findReadableUsersByChannel: jest.fn()};
	const unread = {incrementIfPresent: jest.fn()};
	const service = Object.assign<WebhookService, Record<string, unknown>>(new WebhookService(), {
		webhooks,
		channels,
		readiness,
		limiter,
		messages,
		chat,
		readAccess,
		unread,
	});
	const messageId = new Types.ObjectId();
	const persisted = {
		_id: messageId,
		toObject: () => ({
			_id: messageId, channelId: 8, authorId: null, webhook: {id: 7, name: 'build', avatarUrl: null}, content: 'done', type: 'TEXT', attachments: [],
		}),
	};

	beforeEach(() => {
		jest.resetAllMocks();
		readiness.areReady.mockReturnValue(true);
		webhooks.findByExecutionId.mockResolvedValue({...state});
		channels.findById.mockResolvedValue({
			channelId: 8, type: 'TEXT', teamId: 9, projectId: 10,
		});
		messages.findDelivery.mockResolvedValue(null);
		messages.save.mockResolvedValue({message: persisted, created: true});
		readAccess.findReadableUsersByChannel.mockResolvedValue(new Map([[8, [11]]]));
	});

	it('rejects a management ID or non-v4 UUID as an execution credential', async () => {
		// Given / When / Then
		await Promise.all(['7', '4503599627370496', 'not-a-uuid', executionId.replace('-4f45-', '-1f45-')].map(async invalid =>
			expect(service.execute(invalid, {content: 'done'})).rejects.toBeInstanceOf(BadRequestException)));

		expect(webhooks.findByExecutionId).not.toHaveBeenCalled();
		expect(limiter.acquire).not.toHaveBeenCalled();
		expect(messages.save).not.toHaveBeenCalled();
	});

	it('rejects an unknown execution ID without creating a message', async () => {
		// Given
		webhooks.findByExecutionId.mockResolvedValue(null);

		// When / Then
		await expect(service.execute(executionId, {content: 'done'})).rejects.toBeInstanceOf(NotFoundException);
		expect(limiter.acquire).not.toHaveBeenCalled();
		expect(messages.save).not.toHaveBeenCalled();
	});

	it('requires secure authentication before granting quota or writing a message', async () => {
		// Given
		webhooks.findByExecutionId.mockResolvedValue({...state, isSecure: true});
		messages.findDelivery.mockResolvedValue(persisted);

		// When / Then
		await expect(service.execute(executionId, {content: 'done', idempotencyKey: 'delivery'})).rejects.toBeInstanceOf(UnauthorizedException);
		expect(messages.findDelivery).not.toHaveBeenCalled();
		expect(limiter.acquire).not.toHaveBeenCalled();
		expect(messages.save).not.toHaveBeenCalled();
	});

	it('uses stored public mode and channel scope for the same execution URL regardless of casing', async () => {
		await service.execute(executionId.toUpperCase(), {content: 'done', secret: 'anything'});
		expect(webhooks.findByExecutionId).toHaveBeenCalledWith(executionId);
		expect(limiter.acquire).toHaveBeenCalledWith(7, false);
		expect(messages.save).toHaveBeenCalledWith(expect.objectContaining({teamId: 9, projectId: 10, webhook: state}));
	});

	it('grants the secure quota only after checking the webhook secret', async () => {
		// Given
		webhooks.findByExecutionId.mockResolvedValue({...state, isSecure: true});

		// When
		await service.execute(executionId, {content: 'done', secret: 'secret'});

		// Then
		expect(limiter.acquire).toHaveBeenCalledWith(7, true);
		expect(messages.save).toHaveBeenCalled();
	});

	it('rejects unavailable state, inactive webhooks, deleted webhooks and unsupported channels', async () => {
		readiness.areReady.mockReturnValue(false);
		await expect(service.execute(executionId, {content: 'done'})).rejects.toBeInstanceOf(ServiceUnavailableException);
		readiness.areReady.mockReturnValue(true);
		webhooks.findByExecutionId.mockResolvedValue({...state, isActive: false});
		await expect(service.execute(executionId, {content: 'done'})).rejects.toBeInstanceOf(ForbiddenException);
		webhooks.findByExecutionId.mockResolvedValue({...state, deleted: true});
		await expect(service.execute(executionId, {content: 'done'})).rejects.toBeInstanceOf(NotFoundException);
		webhooks.findByExecutionId.mockResolvedValue(state);
		channels.findById.mockResolvedValue({type: 'DM', teamId: null, projectId: null});
		await expect(service.execute(executionId, {content: 'done'})).rejects.toBeInstanceOf(NotFoundException);
		expect(messages.save).not.toHaveBeenCalled();
	});

	it('does not report a failed HTTP request after the message was persisted', async () => {
		chat.emitToReadableChannelUsers.mockRejectedValue(new Error('socket unavailable'));
		await expect(service.execute(executionId, {content: 'done'})).resolves.toMatchObject({_id: messageId, authorId: null});
	});

	it('returns an accepted delivery even when the new-message quota is exhausted', async () => {
		// Given
		messages.findDelivery.mockResolvedValue(persisted);
		limiter.acquire.mockRejectedValue(new WebhookRateLimitExceeded(60));

		// When
		const response = await service.execute(executionId, {content: 'done', idempotencyKey: 'delivery'});

		// Then
		expect(response).toMatchObject({_id: messageId});
		expect(limiter.acquire).not.toHaveBeenCalled();
		expect(messages.save).not.toHaveBeenCalled();
		expect(chat.emitToReadableChannelUsers).not.toHaveBeenCalled();
		expect(unread.incrementIfPresent).not.toHaveBeenCalled();
	});

	it('rejects a new delivery when its message quota is exhausted', async () => {
		// Given
		limiter.acquire.mockRejectedValue(new WebhookRateLimitExceeded(60));

		// When / Then
		await expect(service.execute(executionId, {content: 'done', idempotencyKey: 'new-delivery'})).rejects.toBeInstanceOf(WebhookRateLimitExceeded);
		expect(messages.save).not.toHaveBeenCalled();
		expect(chat.emitToReadableChannelUsers).not.toHaveBeenCalled();
		expect(unread.incrementIfPresent).not.toHaveBeenCalled();
	});
});
