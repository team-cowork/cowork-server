import { mongo } from 'mongoose';
import { TeamRoleProjectionRepository } from '../repository/team-role-projection.repository';
import { ChannelRolePolicyProjectionRepository } from '../repository/channel-role-policy-projection.repository';
import { ChannelMessageReadAccessService } from '../service/channel-message-read-access.service';
import { TeamRoleEventConsumer } from './team-role-event.consumer';

type ConsumerWithHandle = { handleEvent(payload: unknown, key?: string): Promise<void> };
const SOURCE_VERSION = mongo.Long.fromString('1787702400000000000');

describe('TeamRoleEventConsumer', () => {
    const repository = {
        upsertRole: jest.fn().mockResolvedValue(true),
        deleteRole: jest.fn().mockResolvedValue(true),
        upsertAssignment: jest.fn().mockResolvedValue(true),
        deleteAssignment: jest.fn().mockResolvedValue(true),
        deleteMemberAssignments: jest.fn().mockResolvedValue(true),
    };
    const policyRepository = {
        findChannelIdsByRole: jest.fn().mockResolvedValue([10]),
        findChannelIdsByTeam: jest.fn().mockResolvedValue([10, 20]),
    };
    const accessService = { evictUnauthorizedSockets: jest.fn().mockResolvedValue(undefined) };
    const consumer = new TeamRoleEventConsumer(
        {} as never,
        {} as never,
        repository as unknown as TeamRoleProjectionRepository,
        policyRepository as unknown as ChannelRolePolicyProjectionRepository,
        accessService as unknown as ChannelMessageReadAccessService,
        {} as never,
    );
    const io = {} as never;
    consumer.setSocketServer(io);
    const handle = (payload: unknown, key?: string) =>
        (consumer as unknown as ConsumerWithHandle).handleEvent(payload, key);

    beforeEach(() => jest.clearAllMocks());

    it('ROLE_UPSERTED에서 priority를 projection에 저장하고 영향 room을 재평가한다', async () => {
        await handle({
            eventType: 'ROLE_UPSERTED',
            teamId: 1,
            roleId: 7,
            name: 'reviewer',
            priority: 100,
            permissions: [],
            occurredAt: '2026-08-26T00:00:00Z',
        }, 'role:1:7');

        expect(repository.upsertRole).toHaveBeenCalledWith({
            teamId: 1,
            roleId: 7,
            name: 'reviewer',
            priority: 100,
            permissions: [],
            occurredAt: new Date('2026-08-26T00:00:00Z'),
            sourceVersion: SOURCE_VERSION,
        });
        expect(accessService.evictUnauthorizedSockets).toHaveBeenCalledWith(io, [10], undefined);
    });

    it('MEMBER_ASSIGNMENTS_DELETED는 member tombstone을 반영하고 해당 사용자만 재평가한다', async () => {
        await handle({
            eventType: 'MEMBER_ASSIGNMENTS_DELETED',
            teamId: 1,
            accountId: 42,
            occurredAt: '2026-08-26T00:00:00Z',
        }, 'member:1:42');

        expect(repository.deleteMemberAssignments).toHaveBeenCalledWith(
            1,
            42,
            new Date('2026-08-26T00:00:00Z'),
            SOURCE_VERSION,
        );
        expect(accessService.evictUnauthorizedSockets).toHaveBeenCalledWith(io, [10, 20], [42]);
    });

    it('aggregate key가 다르면 projection을 변경하지 않는다', async () => {
        await expect(handle({
            eventType: 'ASSIGNMENT_UPSERTED',
            teamId: 1,
            accountId: 42,
            roleId: 7,
            occurredAt: '2026-08-26T00:00:00Z',
        }, 'assignment:1:7:42')).rejects.toThrow('team role event key mismatch');
        expect(repository.upsertAssignment).not.toHaveBeenCalled();
    });
});
