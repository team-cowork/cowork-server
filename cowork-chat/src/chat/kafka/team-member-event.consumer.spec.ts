import { mongo } from 'mongoose';
import { TeamMemberProjectionRepository } from '../repository/team-member-projection.repository';
import { TeamMemberEventConsumer } from './team-member-event.consumer';
import { ChannelProjectionRepository } from '../repository/channel-projection.repository';
import { ChannelMessageReadAccessService } from '../service/channel-message-read-access.service';

type ConsumerWithHandle = {
    handleEvent(event: unknown, messageKey?: string): Promise<void>;
};

const SOURCE_VERSION = mongo.Long.fromString('1787702400000000000');

describe('TeamMemberEventConsumer', () => {
    const repository = {
        upsert: jest.fn().mockResolvedValue(undefined),
        remove: jest.fn().mockResolvedValue(undefined),
    };
    const consumer = new TeamMemberEventConsumer(
        { get: jest.fn() } as never,
        { sendCustom: jest.fn() } as never,
        repository as unknown as TeamMemberProjectionRepository,
        {} as never,
        { findIdsByTeamId: jest.fn() } as unknown as ChannelProjectionRepository,
        { evictUnauthorizedSockets: jest.fn() } as unknown as ChannelMessageReadAccessService,
    );
    const handle = (event: unknown, messageKey: string) =>
        (consumer as unknown as ConsumerWithHandle).handleEvent(event, messageKey);

    beforeEach(() => jest.clearAllMocks());

    it('UPSERT 이벤트를 팀 멤버 projection에 저장한다', async () => {
        await handle({
            eventType: 'UPSERT',
            teamId: 10,
            userId: 42,
            role: 'MEMBER',
            teamName: 'backend',
            occurredAt: '2026-08-26T00:00:00',
            snapshot: true,
        }, '10:42');

        expect(repository.upsert).toHaveBeenCalledWith({
            teamId: 10,
            userId: 42,
            role: 'MEMBER',
            teamName: 'backend',
            occurredAt: new Date('2026-08-26T00:00:00Z'),
            sourceVersion: SOURCE_VERSION,
        });
    });

    it('DELETE 이벤트를 팀 멤버 projection에서 삭제한다', async () => {
        await handle({
            eventType: 'DELETE',
            teamId: 10,
            userId: 42,
            role: 'MEMBER',
            teamName: 'backend',
            occurredAt: '2026-08-26T00:00:00',
        }, '10:42');

        expect(repository.remove).toHaveBeenCalledWith(
            10,
            42,
            new Date('2026-08-26T00:00:00Z'),
            SOURCE_VERSION,
        );
    });

    it('같은 DELETE 재처리에서도 현재 미가입 상태면 channel/team room 회수를 복구한다', async () => {
        const memberRepository = {
            remove: jest.fn().mockResolvedValue(false),
            exists: jest.fn().mockResolvedValue(false),
        };
        const channelRepository = { findIdsByTeamId: jest.fn().mockResolvedValue([3, 4]) };
        const accessService = { evictUnauthorizedSockets: jest.fn().mockResolvedValue(undefined) };
        const socketsLeave = jest.fn();
        const emit = jest.fn();
        const socket = {
            in: jest.fn().mockReturnValue({ socketsLeave }),
            to: jest.fn().mockReturnValue({ emit }),
        };
        const isolatedConsumer = new TeamMemberEventConsumer(
            { get: jest.fn() } as never,
            { sendCustom: jest.fn() } as never,
            memberRepository as unknown as TeamMemberProjectionRepository,
            {} as never,
            channelRepository as unknown as ChannelProjectionRepository,
            accessService as unknown as ChannelMessageReadAccessService,
        );
        isolatedConsumer.setSocketServer(socket as never);

        await (isolatedConsumer as unknown as ConsumerWithHandle).handleEvent({
            eventType: 'DELETE',
            teamId: 10,
            userId: 42,
            occurredAt: '2026-08-26T00:00:00Z',
        }, '10:42');

        expect(accessService.evictUnauthorizedSockets).toHaveBeenCalledWith(socket, [3, 4], [42]);
        expect(socketsLeave).toHaveBeenCalledWith('team:10');
        expect(emit).toHaveBeenCalledWith('team:access:revoked', { teamId: 10 });
    });

    it('snapshot DELETE도 알림 재발행 없이 channel/team room을 현재 상태로 복구한다', async () => {
        const memberRepository = {
            remove: jest.fn().mockResolvedValue(true),
            exists: jest.fn().mockResolvedValue(false),
        };
        const channelRepository = { findIdsByTeamId: jest.fn().mockResolvedValue([3]) };
        const accessService = { evictUnauthorizedSockets: jest.fn().mockResolvedValue(undefined) };
        const socketsLeave = jest.fn();
        const emit = jest.fn();
        const socket = {
            in: jest.fn().mockReturnValue({ socketsLeave }),
            to: jest.fn().mockReturnValue({ emit }),
        };
        const isolatedConsumer = new TeamMemberEventConsumer(
            { get: jest.fn() } as never,
            { sendCustom: jest.fn() } as never,
            memberRepository as unknown as TeamMemberProjectionRepository,
            {} as never,
            channelRepository as unknown as ChannelProjectionRepository,
            accessService as unknown as ChannelMessageReadAccessService,
        );
        isolatedConsumer.setSocketServer(socket as never);

        await (isolatedConsumer as unknown as ConsumerWithHandle).handleEvent({
            eventType: 'DELETE',
            teamId: 10,
            userId: 42,
            occurredAt: '2026-08-26T00:00:00Z',
            snapshot: true,
        }, '10:42');

        expect(accessService.evictUnauthorizedSockets).toHaveBeenCalledWith(socket, [3], [42]);
        expect(socketsLeave).toHaveBeenCalledWith('team:10');
        expect(emit).not.toHaveBeenCalled();
    });

    it('composite key가 아닌 이벤트는 계약 오류로 격리 대상이 된다', async () => {
        await expect(handle(
            {
                eventType: 'UPSERT',
                teamId: 10,
                userId: 42,
                role: 'MEMBER',
                teamName: 'backend',
                occurredAt: '2026-08-26T00:00:00',
            },
            '10',
        )).rejects.toThrow('team member event key mismatch');

        expect(repository.upsert).not.toHaveBeenCalled();
    });
});
