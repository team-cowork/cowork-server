import { ProjectionReadinessService } from '../../common/kafka/projection-readiness.service';
import { ChannelMemberRepository } from '../repository/channel-member.repository';
import { ChannelProjectionRepository } from '../repository/channel-projection.repository';
import { ChannelRolePolicyProjectionRepository } from '../repository/channel-role-policy-projection.repository';
import { TeamMemberProjectionRepository } from '../repository/team-member-projection.repository';
import { TeamRoleProjectionRepository } from '../repository/team-role-projection.repository';
import { ChannelMessageReadAccessService } from './channel-message-read-access.service';

describe('ChannelMessageReadAccessService', () => {
    const channelRepository = { findByIds: jest.fn() };
    const channelMemberRepository = { findByChannelIdsAndUserIds: jest.fn() };
    const teamMemberRepository = { findByTeamIdsAndUserIds: jest.fn() };
    const teamRoleRepository = {
        findAssignmentsByTeamIdsAndAccountIds: jest.fn(),
        findRolesByIds: jest.fn(),
    };
    const policyRepository = { findByChannelIdsAndRoleIds: jest.fn() };
    const projectionReadiness = { isReady: jest.fn() };
    const service = new ChannelMessageReadAccessService(
        channelRepository as unknown as ChannelProjectionRepository,
        channelMemberRepository as unknown as ChannelMemberRepository,
        teamMemberRepository as unknown as TeamMemberProjectionRepository,
        teamRoleRepository as unknown as TeamRoleProjectionRepository,
        policyRepository as unknown as ChannelRolePolicyProjectionRepository,
        projectionReadiness as unknown as ProjectionReadinessService,
    );

    beforeEach(() => {
        jest.clearAllMocks();
        projectionReadiness.isReady.mockReturnValue(true);
        channelRepository.findByIds.mockResolvedValue([
            { channelId: 10, teamId: 1, type: 'TEXT', isPrivate: false },
            { channelId: 20, teamId: 1, type: 'TEXT', isPrivate: true },
        ]);
        channelMemberRepository.findByChannelIdsAndUserIds.mockResolvedValue(new Map([
            [10, [1, 2, 3, 4, 5, 6].map((userId) => ({
                channelId: 10,
                userId,
                teamId: 1,
                channelType: 'TEXT',
            }))],
            [20, [{ channelId: 20, userId: 5, teamId: 1, channelType: 'TEXT' }]],
        ]));
        teamMemberRepository.findByTeamIdsAndUserIds.mockResolvedValue([
            { teamId: 1, userId: 1, role: 'OWNER', teamName: 'team' },
            { teamId: 1, userId: 2, role: 'MEMBER', teamName: 'team' },
            { teamId: 1, userId: 3, role: 'MEMBER', teamName: 'team' },
            { teamId: 1, userId: 4, role: 'MEMBER', teamName: 'team' },
            { teamId: 1, userId: 5, role: 'MEMBER', teamName: 'team' },
            { teamId: 1, userId: 6, role: 'MEMBER', teamName: 'team' },
            { teamId: 1, userId: 7, role: 'OWNER', teamName: 'team' },
        ]);
        teamRoleRepository.findAssignmentsByTeamIdsAndAccountIds.mockResolvedValue([
            { teamId: 1, accountId: 2, roleId: 101 },
            { teamId: 1, accountId: 2, roleId: 102 },
            { teamId: 1, accountId: 3, roleId: 201 },
            { teamId: 1, accountId: 3, roleId: 202 },
            { teamId: 1, accountId: 5, roleId: 101 },
            { teamId: 1, accountId: 6, roleId: 301 },
            { teamId: 1, accountId: 6, roleId: 302 },
        ]);
        teamRoleRepository.findRolesByIds.mockResolvedValue([
            { teamId: 1, roleId: 101, priority: 100 },
            { teamId: 1, roleId: 102, priority: 10 },
            { teamId: 1, roleId: 201, priority: 50 },
            { teamId: 1, roleId: 202, priority: 50 },
            { teamId: 1, roleId: 301, priority: 100 },
            { teamId: 1, roleId: 302, priority: 10 },
        ]);
        policyRepository.findByChannelIdsAndRoleIds.mockResolvedValue([
            { teamId: 1, channelId: 10, roleId: 101, messageRead: true },
            { teamId: 1, channelId: 10, roleId: 102, messageRead: false },
            { teamId: 1, channelId: 10, roleId: 201, messageRead: true },
            { teamId: 1, channelId: 10, roleId: 202, messageRead: false },
            { teamId: 1, channelId: 20, roleId: 101, messageRead: true },
            { teamId: 1, channelId: 10, roleId: 302, messageRead: true },
        ]);
    });

    describe('canReadChannel', () => {
    it('가장 높은 priority의 명시값을 적용하고 낮은 priority deny는 무시한다', async () => {
        await expect(service.canReadChannel(10, 2)).resolves.toBe(true);
    });

    it('같은 priority에서 allow와 deny가 충돌하면 deny를 적용한다', async () => {
        await expect(service.canReadChannel(10, 3)).resolves.toBe(false);
    });

    it('어떤 role에도 message_read가 명시되지 않으면 기본 거부한다', async () => {
        await expect(service.canReadChannel(10, 4)).resolves.toBe(false);
    });

    it('상위 priority role이 미지정이면 하위 priority의 명시 allow를 상속한다', async () => {
        await expect(service.canReadChannel(10, 6)).resolves.toBe(true);
    });

    it('built-in OWNER는 role policy 없이 공개 채널을 읽을 수 있다', async () => {
        await expect(service.canReadChannel(10, 1)).resolves.toBe(true);
    });

    it('공개 채널 메타데이터는 볼 수 있어도 미가입자는 메시지를 읽을 수 없다', async () => {
        await expect(service.canReadChannel(10, 7)).resolves.toBe(false);
        await expect(service.filterVisibleChannelIds(1, 7, [10])).resolves.toEqual([10]);
    });

    it('비공개 채널은 OWNER도 active 채널 멤버십을 우회하지 못한다', async () => {
        await expect(service.canReadChannel(20, 1)).resolves.toBe(false);
        await expect(service.canReadChannel(20, 5)).resolves.toBe(true);
    });

    it('채널 원본 정보가 없으면 DM 멤버십이 남아 있어도 거부한다', async () => {
        channelRepository.findByIds.mockResolvedValue([]);
        channelMemberRepository.findByChannelIdsAndUserIds.mockResolvedValue(new Map([
            [99, [{ channelId: 99, userId: 5, teamId: null, channelType: 'DM' }]],
        ]));

        await expect(service.canReadChannel(99, 5)).resolves.toBe(false);
    });
    });

    describe('evaluateMany', () => {
    it('teamId/type 조합이 정확한 DM만 멤버십으로 읽을 수 있다', async () => {
        channelRepository.findByIds.mockResolvedValue([
            { channelId: 30, teamId: null, type: 'TEXT', isPrivate: false },
            { channelId: 31, teamId: 1, type: 'DM', isPrivate: false },
            { channelId: 32, teamId: null, type: 'DM', isPrivate: false },
        ]);
        channelMemberRepository.findByChannelIdsAndUserIds.mockResolvedValue(new Map([
            [30, [{ channelId: 30, userId: 5 }]],
            [31, [{ channelId: 31, userId: 5 }]],
            [32, [{ channelId: 32, userId: 5 }]],
        ]));

        const access = await service.evaluateMany([30, 31, 32].map((channelId) => ({ channelId, userId: 5 })));

        expect(access.get('30:5')).toBe(false);
        expect(access.get('31:5')).toBe(false);
        expect(access.get('32:5')).toBe(true);
    });
    });

    describe('evictUnauthorizedSockets', () => {
    it('권한 회수 시 거부된 socket만 채널 room에서 강제 퇴장시킨다', async () => {
        const allowedSocket = {
            data: { userId: 2 },
            rooms: new Set(['chat:10']),
            leave: jest.fn().mockResolvedValue(undefined),
            emit: jest.fn(),
        };
        const deniedSocket = {
            data: { userId: 3 },
            rooms: new Set(['chat:10']),
            leave: jest.fn().mockResolvedValue(undefined),
            emit: jest.fn(),
        };
        const io = {
            in: jest.fn().mockReturnValue({ fetchSockets: jest.fn().mockResolvedValue([allowedSocket, deniedSocket]) }),
        };

        await service.evictUnauthorizedSockets(io as never, [10]);

        expect(allowedSocket.leave).not.toHaveBeenCalled();
        expect(deniedSocket.leave).toHaveBeenCalledWith('chat:10');
        expect(deniedSocket.emit).toHaveBeenCalledWith('channel:access:revoked', { channelId: 10 });
    });
    });

    describe('emitToReadableChannelUsers', () => {
    it('실시간 이벤트는 읽기 가능한 socket에만 보내고 요청한 sender는 제외한다', async () => {
        const sender = { id: 'sender', data: { userId: 2 }, emit: jest.fn() };
        const otherAllowed = { id: 'allowed', data: { userId: 2 }, emit: jest.fn() };
        const denied = { id: 'denied', data: { userId: 3 }, emit: jest.fn() };
        const io = {
            in: jest.fn().mockReturnValue({ fetchSockets: jest.fn().mockResolvedValue([sender, otherAllowed, denied]) }),
        };

        await service.emitToReadableChannelUsers(io as never, 10, 'typing', { value: true }, 'sender');

        expect(sender.emit).not.toHaveBeenCalled();
        expect(otherAllowed.emit).toHaveBeenCalledWith('typing', { value: true });
        expect(denied.emit).not.toHaveBeenCalled();
    });
    });

    describe('emitToActiveTeamUsers', () => {
    it('팀 범위 이벤트는 탈퇴한 구독자를 제외하고 활성 팀 멤버에게만 보낸다', async () => {
        const active = { id: 'active', data: { userId: 2 }, emit: jest.fn() };
        const stale = { id: 'stale', data: { userId: 99 }, emit: jest.fn() };
        const io = {
            in: jest.fn().mockReturnValue({ fetchSockets: jest.fn().mockResolvedValue([active, stale]) }),
        };
        teamMemberRepository.findByTeamIdsAndUserIds.mockResolvedValueOnce([
            { teamId: 1, userId: 2, role: 'MEMBER', teamName: 'team' },
        ]);

        await service.emitToActiveTeamUsers(io as never, 1, 'project:updated', { projectId: 5 });

        expect(teamMemberRepository.findByTeamIdsAndUserIds).toHaveBeenCalledWith([1], [2, 99]);
        expect(active.emit).toHaveBeenCalledWith('project:updated', { projectId: 5 });
        expect(stale.emit).not.toHaveBeenCalled();
    });
    });
});
