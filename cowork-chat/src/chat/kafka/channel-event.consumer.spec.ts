import { mongo } from 'mongoose';
import { ChannelProjectionRepository } from '../repository/channel-projection.repository';
import { ChannelEventConsumer } from './channel-event.consumer';

type ConsumerWithHandle = {
    handleEvent(event: unknown, messageKey?: string): Promise<void>;
};

const SOURCE_VERSION = mongo.Long.fromString('1787702400000000000');

describe('ChannelEventConsumer', () => {
    const repository = {
        upsert: jest.fn().mockResolvedValue(undefined),
        remove: jest.fn().mockResolvedValue(undefined),
    };
    const consumer = new ChannelEventConsumer(
        { get: jest.fn() } as never,
        { sendCustom: jest.fn() } as never,
        repository as unknown as ChannelProjectionRepository,
        {} as never,
    );
    const socket = { to: jest.fn().mockReturnValue({ emit: jest.fn() }) };
    consumer.setSocketServer(socket as never);
    const handle = (event: unknown, messageKey: string) =>
        (consumer as unknown as ConsumerWithHandle).handleEvent(event, messageKey);

    beforeEach(() => jest.clearAllMocks());

    it.each(['CREATED', 'UPDATED'] as const)('%s 이벤트를 socket 초기화 전에도 projection에 반영한다', async (eventType) => {
        const event = {
            eventType,
            channelId: 3,
            teamId: 10,
            projectId: 7,
            name: '배포',
            type: 'TEXT',
            viewType: 'CHAT',
            description: null,
            isPrivate: false,
            position: 2,
            occurredAt: '2026-08-26T00:00:00',
        };

        await handle(event, '3');

        expect(repository.upsert).toHaveBeenCalledWith({
            ...event,
            occurredAt: new Date('2026-08-26T00:00:00Z'),
            sourceVersion: SOURCE_VERSION,
        });
    });

    it('DELETED 이벤트를 projection에 반영한다', async () => {
        await handle({
            eventType: 'DELETED',
            channelId: 3,
            teamId: 10,
            projectId: 7,
            name: '배포',
            type: 'TEXT',
            viewType: 'CHAT',
            description: null,
            isPrivate: false,
            position: 2,
            occurredAt: '2026-08-26T00:00:00',
        }, '3');

        expect(repository.remove).toHaveBeenCalledWith(
            3,
            new Date('2026-08-26T00:00:00Z'),
            SOURCE_VERSION,
        );
        expect(repository.upsert).not.toHaveBeenCalled();
    });

    it('이미 반영된 UPDATED snapshot이면 socket 이벤트를 다시 보내지 않는다', async () => {
        repository.upsert.mockResolvedValueOnce(false);

        await handle({
            eventType: 'UPDATED',
            channelId: 3,
            teamId: 10,
            name: '배포',
            type: 'TEXT',
            viewType: 'CHAT',
            description: null,
            isPrivate: false,
            position: 2,
            occurredAt: '2026-08-26T00:00:00Z',
        }, '3');

        expect(socket.to).not.toHaveBeenCalled();
    });

    it('snapshot=true이면 projection은 갱신하지만 socket 변경 알림은 보내지 않는다', async () => {
        repository.upsert.mockResolvedValueOnce(true);

        await handle({
            eventType: 'UPDATED',
            channelId: 3,
            teamId: 10,
            name: '배포',
            type: 'TEXT',
            viewType: 'CHAT',
            description: null,
            isPrivate: false,
            position: 2,
            occurredAt: '2026-08-26T00:00:00Z',
            snapshot: true,
        }, '3');

        expect(repository.upsert).toHaveBeenCalled();
        expect(socket.to).not.toHaveBeenCalled();
    });

    it('channelId가 아닌 key 이벤트는 계약 오류로 격리 대상이 된다', async () => {
        await expect((consumer as unknown as ConsumerWithHandle).handleEvent({
            eventType: 'CREATED',
            channelId: 3,
            teamId: 10,
            name: '배포',
            type: 'TEXT',
            viewType: 'CHAT',
            description: null,
            isPrivate: false,
            occurredAt: '2026-08-26T00:00:00',
        }, '10')).rejects.toThrow('channel event key does not match channelId');

        expect(repository.upsert).not.toHaveBeenCalled();
    });
});
