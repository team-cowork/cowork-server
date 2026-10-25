import {ConflictException, GoneException} from '@nestjs/common';
import {MessageRepository} from '../repository/message.repository';
import {WebhookMessageRepository} from './webhook-message.repository';
import {webhookRequestHash} from './webhook-policy';

const webhook = {
    webhookId: 7, executionId: '6cf6eda2-99a4-4f45-9bc9-14292c607df0', channelId: 8, name: 'build', avatarUrl: null, isSecure: false, secretHash: null, isActive: true, deleted: false,
};
const input = {
    webhook, teamId: 9, projectId: 10, content: 'done', deliveryKey: 'delivery', requestHash: webhookRequestHash('done'),
};

describe('WebhookMessageRepository delivery decisions', () => {
    const model = {findOne: jest.fn(), create: jest.fn()};
    const repository = new WebhookMessageRepository(model as never);

    beforeEach(() => {
        jest.resetAllMocks();
    });

    it('returns the original persisted message on a matching delivery', async () => {
        const existing = {_id: 'first', webhookRequestHash: input.requestHash};
        model.findOne.mockResolvedValue(existing);
        await expect(repository.save(input)).resolves.toEqual({message: existing, created: false});
        expect(model.create).not.toHaveBeenCalled();
        expect(model.findOne).toHaveBeenCalledWith({'webhook.id': 7, webhookDeliveryKey: 'delivery'});
    });

    it('rejects a changed body using the same webhook delivery key', async () => {
        model.findOne.mockResolvedValue({webhookRequestHash: webhookRequestHash('other')});
        await expect(repository.save(input)).rejects.toBeInstanceOf(ConflictException);
        expect(model.create).not.toHaveBeenCalled();
    });

    it('recovers a concurrent identical delivery from the unique-index conflict', async () => {
        const winner = {_id: 'winner', webhookRequestHash: input.requestHash};
        model.findOne.mockResolvedValueOnce(null).mockResolvedValueOnce(winner);
        model.create.mockRejectedValue({code: 11_000});
        await expect(repository.save(input)).resolves.toEqual({message: winner, created: false});
    });

    it('does not resurrect a deleted accepted delivery', async () => {
        model.findOne.mockResolvedValue({webhookDeleted: true, webhookRequestHash: input.requestHash});
        await expect(repository.save(input)).rejects.toBeInstanceOf(GoneException);
        expect(model.create).not.toHaveBeenCalled();
        model.findOne.mockResolvedValue({searchIndexStatus: 'DELETING', webhookRequestHash: input.requestHash});
        await expect(repository.save(input)).rejects.toBeInstanceOf(GoneException);
    });

    it('deletes an accepted delivery atomically while retaining only the durable receipt', async () => {
        const deletionModel = {
            updateOne: jest.fn().mockResolvedValue({acknowledged: true, matchedCount: 1}),
            deleteOne: jest.fn(),
        };
        const messages = new MessageRepository(deletionModel as never);
        await expect(messages.deleteById('accepted')).resolves.toEqual({acknowledged: true, deletedCount: 1});
        expect(deletionModel.deleteOne).not.toHaveBeenCalled();
        expect(deletionModel.updateOne).toHaveBeenCalledWith(
            {_id: 'accepted', webhookDeliveryKey: {$type: 'string'}},
            [{
                $replaceWith: {
                    _id: '$_id',
                    webhook: {id: '$webhook.id'},
                    webhookDeliveryKey: '$webhookDeliveryKey',
                    webhookRequestHash: '$webhookRequestHash',
                    webhookDeleted: true,
                },
            }],
            {timestamps: false, updatePipeline: true},
        );
        model.findOne.mockResolvedValue({webhookDeleted: true, webhookRequestHash: input.requestHash});
        await expect(repository.save(input)).rejects.toBeInstanceOf(GoneException);
        expect(model.create).not.toHaveBeenCalled();
    });

    it('does not conflate deliveries without a key and never uses a user as webhook author', async () => {
        model.create.mockResolvedValue({_id: 'new'});
        await repository.save({...input, deliveryKey: undefined});
        await repository.save({...input, deliveryKey: undefined});
        expect(model.findOne).not.toHaveBeenCalled();
        expect(model.create).toHaveBeenCalledTimes(2);
        expect(model.create).toHaveBeenCalledWith(expect.objectContaining({
            authorId: null, webhook: {id: 7, name: 'build', avatarUrl: null}, mentions: [], notificationStatus: 'SENT',
        }));
        expect((model.create.mock.calls as unknown[][])[0][0]).not.toHaveProperty('webhookDeliveryKey');
    });
});
