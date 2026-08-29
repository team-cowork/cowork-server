import { mongo } from 'mongoose';
import { ChannelRolePolicyProjectionRepository } from '../repository/channel-role-policy-projection.repository';
import { ChannelMessageReadAccessService } from '../service/channel-message-read-access.service';
import { ChannelRolePolicyEventConsumer } from './channel-role-policy-event.consumer';

type ConsumerWithHandle = { handleEvent(payload: unknown, key?: string): Promise<void> };
const SOURCE_VERSION = mongo.Long.fromString('1787702400000000000');

describe('ChannelRolePolicyEventConsumer', () => {
    const repository = {
        upsert: jest.fn().mockResolvedValue(true),
        remove: jest.fn().mockResolvedValue(true),
    };
    const accessService = { evictUnauthorizedSockets: jest.fn().mockResolvedValue(undefined) };
    const consumer = new ChannelRolePolicyEventConsumer(
        {} as never,
        {} as never,
        repository as unknown as ChannelRolePolicyProjectionRepository,
        accessService as unknown as ChannelMessageReadAccessService,
        {} as never,
    );
    const io = {} as never;
    consumer.setSocketServer(io);
    const handle = (payload: unknown, key?: string) =>
        (consumer as unknown as ConsumerWithHandle).handleEvent(payload, key);

    beforeEach(() => jest.clearAllMocks());

    it('message_read deny를 projection에 반영한 뒤 channel room을 재평가한다', async () => {
        await handle({
            schemaVersion: 1,
            eventType: 'UPSERT',
            teamId: 1,
            channelId: 10,
            roleId: 7,
            permissions: { message_read: false },
            occurredAt: '2026-08-26T00:00:00Z',
            snapshot: false,
        }, 'policy:1:10:7');

        expect(repository.upsert).toHaveBeenCalledWith({
            teamId: 1,
            channelId: 10,
            roleId: 7,
            messageRead: false,
            occurredAt: new Date('2026-08-26T00:00:00Z'),
            sourceVersion: SOURCE_VERSION,
        });
        expect(accessService.evictUnauthorizedSockets).toHaveBeenCalledWith(io, [10]);
    });

    it('snapshot projection도 room 권한을 재평가해 누락된 회수를 복구한다', async () => {
        await handle({
            schemaVersion: 1,
            eventType: 'UPSERT',
            teamId: 1,
            channelId: 10,
            roleId: 7,
            permissions: { message_read: true },
            occurredAt: '2026-08-26T00:00:00Z',
            snapshot: true,
        }, 'policy:1:10:7');

        expect(repository.upsert).toHaveBeenCalled();
        expect(accessService.evictUnauthorizedSockets).toHaveBeenCalledWith(io, [10]);
    });

    it('DELETE에는 permissions=null과 정확한 compacted key가 필요하다', async () => {
        await handle({
            schemaVersion: 1,
            eventType: 'DELETE',
            teamId: 1,
            channelId: 10,
            roleId: 7,
            permissions: null,
            occurredAt: '2026-08-26T00:00:00Z',
        }, 'policy:1:10:7');

        expect(repository.remove).toHaveBeenCalledWith(
            1,
            10,
            7,
            new Date('2026-08-26T00:00:00Z'),
            SOURCE_VERSION,
        );
    });
});
