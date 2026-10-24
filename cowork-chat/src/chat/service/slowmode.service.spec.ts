import {HttpException} from '@nestjs/common';
import {canBypassSlowmode, SlowmodeService} from './slowmode.service';

const role = (roleId: number, priority: number, permissions: Record<string, boolean> = {}) => ({
    roleId, teamId: 10, priority, permissions,
});

describe('canBypassSlowmode', () => {
    it('OWNER와 ADMIN은 역할 권한과 무관하게 우회한다', () => {
        for (const teamRole of ['OWNER', 'ADMIN']) {
            expect(canBypassSlowmode({
                teamId: 10,
                teamRole,
                roles: [],
                channelPermissionsByRoleId: new Map(),
            })).toBe(true);
        }
    });

    it('채널 덮어쓰기가 없으면 역할 기본 권한 중 하나라도 허용이면 우회한다', () => {
        expect(canBypassSlowmode({
            teamId: 10,
            teamRole: 'MEMBER',
            roles: [role(1, 5), role(2, 1, {slowmode_bypass: true})],
            channelPermissionsByRoleId: new Map(),
        })).toBe(true);
        expect(canBypassSlowmode({
            teamId: 10,
            teamRole: 'MEMBER',
            roles: [role(1, 5, {slowmode_bypass: false})],
            channelPermissionsByRoleId: new Map(),
        })).toBe(false);
    });

    it('채널 덮어쓰기는 priority가 가장 높은 역할을 따르고 같은 priority에서는 거부가 이긴다', () => {
        const roles = [role(1, 5, {slowmode_bypass: true}), role(2, 9), role(3, 9)];

        expect(canBypassSlowmode({
            teamId: 10,
            teamRole: 'MEMBER',
            roles,
            channelPermissionsByRoleId: new Map([[1, {slowmode_bypass: true}], [2, {slowmode_bypass: false}]]),
        })).toBe(false);
        expect(canBypassSlowmode({
            teamId: 10,
            teamRole: 'MEMBER',
            roles,
            channelPermissionsByRoleId: new Map([[2, {slowmode_bypass: true}], [3, {slowmode_bypass: false}]]),
        })).toBe(false);
        expect(canBypassSlowmode({
            teamId: 10,
            teamRole: 'MEMBER',
            roles: [role(1, 5)],
            channelPermissionsByRoleId: new Map([[1, {slowmode_bypass: true}]]),
        })).toBe(true);
    });

    it('다른 팀 역할은 평가하지 않는다', () => {
        expect(canBypassSlowmode({
            teamId: 10,
            teamRole: 'MEMBER',
            roles: [{
                roleId: 1,
                teamId: 99,
                priority: 1,
                permissions: {slowmode_bypass: true},
            }],
            channelPermissionsByRoleId: new Map(),
        })).toBe(false);
    });
});

describe('SlowmodeService.reserve', () => {
    const settingRepository = {findSlowmodeSeconds: jest.fn()};
    const teamMemberRepository = {findByTeamIdsAndUserIds: jest.fn()};
    const teamRoleRepository = {
        findAssignmentsByTeamIdsAndAccountIds: jest.fn(),
        findRolePermissionsByIds: jest.fn(),
    };
    const policyRepository = {findPermissionsByChannelIdAndRoleIds: jest.fn()};
    const rateLimiter = {tryReserve: jest.fn(), release: jest.fn()};
    const service = new SlowmodeService(
        settingRepository as never,
        teamMemberRepository as never,
        teamRoleRepository as never,
        policyRepository as never,
        rateLimiter as never,
    );

    beforeEach(() => {
        jest.resetAllMocks();
        teamMemberRepository.findByTeamIdsAndUserIds.mockResolvedValue([{teamId: 10, userId: 42, role: 'MEMBER'}]);
        teamRoleRepository.findAssignmentsByTeamIdsAndAccountIds.mockResolvedValue([]);
        teamRoleRepository.findRolePermissionsByIds.mockResolvedValue([]);
        policyRepository.findPermissionsByChannelIdAndRoleIds.mockResolvedValue([]);
    });

    it('슬로우 모드가 꺼져 있거나 DM이면 예약하지 않는다', async () => {
        settingRepository.findSlowmodeSeconds.mockResolvedValue(0);

        await expect(service.reserve(1, 10, 42, 'client-1')).resolves.toBeUndefined();
        await expect(service.reserve(1, null, 42, 'client-1')).resolves.toBeUndefined();
        expect(rateLimiter.tryReserve).not.toHaveBeenCalled();
    });

    it('간격만큼 사용자별 key를 clientMessageId로 예약하고 다른 메시지로 점유 중이면 429를 던진다', async () => {
        settingRepository.findSlowmodeSeconds.mockResolvedValue(30);
        rateLimiter.tryReserve.mockResolvedValueOnce('RESERVED').mockResolvedValueOnce('BUSY');

        await expect(service.reserve(1, 10, 42, 'client-1'))
            .resolves.toEqual({key: 'chat:slowmode:1:42', holder: 'client-1'});
        await expect(service.reserve(1, 10, 42, 'client-2')).rejects.toThrow(HttpException);
        expect(rateLimiter.tryReserve).toHaveBeenCalledWith('chat:slowmode:1:42', 'client-1', 30_000);
    });

    it('같은 clientMessageId의 재시도는 통과시키고 해제할 예약을 돌려주지 않는다', async () => {
        settingRepository.findSlowmodeSeconds.mockResolvedValue(30);
        rateLimiter.tryReserve.mockResolvedValue('HELD_BY_SAME_HOLDER');

        await expect(service.reserve(1, 10, 42, 'client-1')).resolves.toBeUndefined();
    });

    it('ADMIN은 예약하지 않는다', async () => {
        settingRepository.findSlowmodeSeconds.mockResolvedValue(30);
        teamMemberRepository.findByTeamIdsAndUserIds.mockResolvedValue([{teamId: 10, userId: 42, role: 'ADMIN'}]);

        await expect(service.reserve(1, 10, 42, 'client-1')).resolves.toBeUndefined();
        expect(rateLimiter.tryReserve).not.toHaveBeenCalled();
    });
});
