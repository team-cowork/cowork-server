import { mongo } from 'mongoose';
import { ChannelProjectionRepository } from '../repository/channel-projection.repository';
import { ChannelEventConsumer } from './channel-event.consumer';
import { ChannelMessageReadAccessService } from '../service/channel-message-read-access.service';

type ConsumerWithHandle = {
    handleEvent(event: unknown, messageKey?: string): Promise<void>;
};

const SOURCE_VERSION = mongo.Long.fromString('1787702400000000000');

describe('ChannelEventConsumer', () => {
    const repository = {
        upsert: jest.fn().mockResolvedValue(undefined),
        remove: jest.fn().mockResolvedValue(undefined),
        findById: jest.fn().mockResolvedValue(null),
    };
    const socketEmit = jest.fn();
    const socketsLeave = jest.fn();
    const socket = {
        to: jest.fn((room: string) => {
            void room;
            return { emit: socketEmit };
        }),
        in: jest.fn((room: string) => {
            void room;
            return { socketsLeave };
        }),
    };
    const accessService = {
        emitChannelEventToVisibleTeamUsers: jest.fn().mockImplementation(
            (io: typeof socket, teamId: number, _channelId: number, event: string, payload: unknown) => {
                io.to(`team:${teamId}`).emit(event, payload);
                return Promise.resolve();
            },
        ),
        captureVisibleTeamSocketIds: jest.fn().mockResolvedValue(['socket-allowed']),
        evictUnauthorizedSockets: jest.fn().mockResolvedValue(undefined),
    };
    const consumer = new ChannelEventConsumer(
        { get: jest.fn() } as never,
        { sendCustom: jest.fn() } as never,
        repository as unknown as ChannelProjectionRepository,
        {} as never,
        accessService as unknown as ChannelMessageReadAccessService,
    );
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
        if (eventType === 'UPDATED') {
            expect(accessService.evictUnauthorizedSockets).toHaveBeenCalledWith(socket, [3]);
        } else {
            expect(accessService.evictUnauthorizedSockets).not.toHaveBeenCalled();
        }
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
        expect(accessService.captureVisibleTeamSocketIds).toHaveBeenCalledWith(socket, 10, 3);
        expect(socket.to).toHaveBeenCalledWith('socket-allowed');
        expect(socket.in).toHaveBeenCalledWith('chat:3');
        expect(socketsLeave).toHaveBeenCalledWith('chat:3');
    });

    it('이미 반영된 UPDATED 재처리도 room 회수는 복구하고 metadata 이벤트는 중복 전송하지 않는다', async () => {
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

        expect(accessService.evictUnauthorizedSockets).toHaveBeenCalledWith(socket, [3]);
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
        expect(accessService.evictUnauthorizedSockets).toHaveBeenCalledWith(socket, [3]);
        expect(socket.to).not.toHaveBeenCalled();
    });

    it('snapshot DELETED도 live 알림 없이 channel room을 정리한다', async () => {
        repository.remove.mockResolvedValueOnce(true);

        await handle({
            eventType: 'DELETED',
            channelId: 3,
            teamId: 10,
            occurredAt: '2026-08-26T00:00:00Z',
            snapshot: true,
        }, '3');

        expect(repository.remove).toHaveBeenCalled();
        expect(accessService.captureVisibleTeamSocketIds).not.toHaveBeenCalled();
        expect(socketsLeave).toHaveBeenCalledWith('chat:3');
        expect(socket.to).not.toHaveBeenCalled();
    });

    it('stale snapshot DELETED 뒤 채널이 재생성됐으면 channel room을 유지한다', async () => {
        repository.remove.mockResolvedValueOnce(false);
        repository.findById.mockResolvedValueOnce({ channelId: 3 });

        await handle({
            eventType: 'DELETED',
            channelId: 3,
            teamId: 10,
            occurredAt: '2026-08-26T00:00:00Z',
            snapshot: true,
        }, '3');

        expect(repository.findById).toHaveBeenCalledWith(3);
        expect(socketsLeave).not.toHaveBeenCalled();
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
