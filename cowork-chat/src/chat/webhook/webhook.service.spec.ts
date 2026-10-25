import {createHash} from 'node:crypto';
import {
    UnauthorizedException,
    ServiceUnavailableException,
    ForbiddenException,
    NotFoundException,
} from '@nestjs/common';
import {Types} from 'mongoose';
import {WebhookRateLimitExceeded} from './webhook-rate-limiter';
import {WebhookService} from './webhook.service';

const state = {
    webhookId: 7, channelId: 8, name: 'build', avatarUrl: null, isSecure: false,
    secretHash: createHash('sha256').update('secret').digest('hex'), isActive: true, deleted: false,
};

describe('WebhookService.execute', () => {
    const webhooks = {findById: jest.fn()};
    const channels = {findById: jest.fn()};
    const readiness = {areReady: jest.fn()};
    const limiter = {acquire: jest.fn()};
    const messages = {findDelivery: jest.fn(), save: jest.fn()};
    const chat = {emitToReadableChannelUsers: jest.fn()};
    const readAccess = {findReadableUsersByChannel: jest.fn()};
    const unread = {incrementIfPresent: jest.fn()};
    const service = new WebhookService(webhooks as never, channels as never, readiness as never, limiter as never, messages as never, chat as never, readAccess as never, unread as never);
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
        webhooks.findById.mockResolvedValue({...state});
        channels.findById.mockResolvedValue({
            channelId: 8, type: 'TEXT', teamId: 9, projectId: 10,
        });
        messages.findDelivery.mockResolvedValue(null);
        messages.save.mockResolvedValue({message: persisted, created: true});
        readAccess.findReadableUsersByChannel.mockResolvedValue(new Map([[8, [11]]]));
    });

    it('requires secure authentication before granting quota or writing a message', async () => {
        // Given
        webhooks.findById.mockResolvedValue({...state, isSecure: true});
        messages.findDelivery.mockResolvedValue(persisted);

        // When / Then
        await expect(service.execute(7, {content: 'done', clientIp: '192.0.2.1', idempotencyKey: 'delivery'})).rejects.toBeInstanceOf(UnauthorizedException);
        expect(messages.findDelivery).not.toHaveBeenCalled();
        expect(limiter.acquire).not.toHaveBeenCalled();
        expect(messages.save).not.toHaveBeenCalled();
    });

    it('uses stored public mode and channel scope despite a supplied secret', async () => {
        await service.execute(7, {content: 'done', clientIp: '192.0.2.1', secret: 'anything'});
        expect(limiter.acquire).toHaveBeenCalledWith(7, false, '192.0.2.1');
        expect(messages.save).toHaveBeenCalledWith(expect.objectContaining({teamId: 9, projectId: 10, webhook: state}));
    });

    it('rejects unavailable state, inactive webhooks, deleted webhooks and unsupported channels', async () => {
        readiness.areReady.mockReturnValue(false);
        await expect(service.execute(7, {content: 'done'})).rejects.toBeInstanceOf(ServiceUnavailableException);
        readiness.areReady.mockReturnValue(true);
        webhooks.findById.mockResolvedValue({...state, isActive: false});
        await expect(service.execute(7, {content: 'done'})).rejects.toBeInstanceOf(ForbiddenException);
        webhooks.findById.mockResolvedValue({...state, deleted: true});
        await expect(service.execute(7, {content: 'done'})).rejects.toBeInstanceOf(NotFoundException);
        webhooks.findById.mockResolvedValue(state);
        channels.findById.mockResolvedValue({type: 'DM', teamId: null, projectId: null});
        await expect(service.execute(7, {content: 'done'})).rejects.toBeInstanceOf(NotFoundException);
        expect(messages.save).not.toHaveBeenCalled();
    });

    it('does not report a failed HTTP request after the message was persisted', async () => {
        chat.emitToReadableChannelUsers.mockRejectedValue(new Error('socket unavailable'));
        await expect(service.execute(7, {content: 'done', clientIp: '192.0.2.1'})).resolves.toMatchObject({_id: messageId, authorId: null});
    });

    it('returns an accepted delivery even when the new-message quota is exhausted', async () => {
        // Given
        messages.findDelivery.mockResolvedValue(persisted);
        limiter.acquire.mockRejectedValue(new WebhookRateLimitExceeded(60));

        // When
        const response = await service.execute(7, {content: 'done', clientIp: '192.0.2.1', idempotencyKey: 'delivery'});

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
        await expect(service.execute(7, {content: 'done', clientIp: '192.0.2.1', idempotencyKey: 'new-delivery'})).rejects.toBeInstanceOf(WebhookRateLimitExceeded);
        expect(messages.save).not.toHaveBeenCalled();
        expect(chat.emitToReadableChannelUsers).not.toHaveBeenCalled();
        expect(unread.incrementIfPresent).not.toHaveBeenCalled();
    });
});
