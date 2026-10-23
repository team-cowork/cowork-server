import {HttpException, HttpStatus, Injectable} from '@nestjs/common';
import {RedisRateLimiter} from '../../common/util/redis-rate-limiter';
import {
    ChannelRolePolicyProjectionRepository,
    TeamMemberProjectionRepository,
    TeamRoleProjectionRepository,
    TextChannelSettingProjectionRepository,
} from '../repository';

export const SLOWMODE_BYPASS_PERMISSION = 'slowmode_bypass';
const BYPASS_TEAM_ROLES = new Set(['OWNER', 'ADMIN']);

export type SlowmodeBypassInput = {
    teamId: number;
    teamRole: string | undefined;
    roles: Array<{roleId: number; teamId: number; priority: number; permissions: Record<string, boolean>}>;
    channelPermissionsByRoleId: Map<number, Record<string, boolean>>;
};

/**
 OWNER, ADMIN은 항상 우회한다. 그 외에는 채널 덮어쓰기에 `slowmode_bypass` 명시값이 있는 역할 중 priority가 가장 높은 값을
 따르고(같은 priority에서 충돌하면 거부), 명시값이 없으면 역할 기본 권한 중 하나라도 허용이면 우회한다.
 */
export function canBypassSlowmode(input: SlowmodeBypassInput): boolean {
    if (input.teamRole !== undefined && BYPASS_TEAM_ROLES.has(input.teamRole)) {
        return true;
    }

    const roles = input.roles.filter(role => role.teamId === input.teamId);
    let selectedPriority: number | undefined;
    let isSelected = false;
    for (const role of roles) {
        const override = input.channelPermissionsByRoleId.get(role.roleId)?.[SLOWMODE_BYPASS_PERMISSION];
        if (typeof override !== 'boolean') {
            continue;
        }

        if (selectedPriority === undefined || role.priority > selectedPriority) {
            selectedPriority = role.priority;
            isSelected = override;
        } else if (role.priority === selectedPriority && !override) {
            isSelected = false;
        }
    }

    return selectedPriority === undefined
        ? roles.some(role => role.permissions[SLOWMODE_BYPASS_PERMISSION] ?? false)
        : isSelected;
}

@Injectable()
export class SlowmodeService {
    constructor(
        private readonly settingRepository: TextChannelSettingProjectionRepository,
        private readonly teamMemberRepository: TeamMemberProjectionRepository,
        private readonly teamRoleRepository: TeamRoleProjectionRepository,
        private readonly policyRepository: ChannelRolePolicyProjectionRepository,
        private readonly rateLimiter: RedisRateLimiter,
    ) {}

    /**
     슬로우 모드 간격을 예약하고, 전송이 실패하면 {@link release}에 넘길 key를 돌려준다. 예약할 필요가 없으면 `undefined`다.

     @throws HttpException 429 간격 안에 다시 보낸 경우
     */
    async reserve(channelId: number, teamId: number | null | undefined, userId: number): Promise<string | undefined> {
        if (teamId === null || teamId === undefined) {
            return undefined;
        }

        const slowmodeSeconds = await this.settingRepository.findSlowmodeSeconds(channelId);
        if (slowmodeSeconds <= 0 || await this.canBypass(teamId, channelId, userId)) {
            return undefined;
        }

        const key = `chat:slowmode:${channelId}:${userId}`;
        if (!(await this.rateLimiter.tryReserve(key, slowmodeSeconds * 1000))) {
            throw new HttpException(
                '슬로우 모드가 켜진 채널입니다. 잠시 후 다시 시도하십시오.',
                HttpStatus.TOO_MANY_REQUESTS,
            );
        }

        return key;
    }

    async release(key: string | undefined): Promise<void> {
        if (key !== undefined) {
            await this.rateLimiter.release(key);
        }
    }

    private async canBypass(teamId: number, channelId: number, userId: number): Promise<boolean> {
        const [member] = await this.teamMemberRepository.findByTeamIdsAndUserIds([teamId], [userId]);
        if (member !== undefined && BYPASS_TEAM_ROLES.has(member.role)) {
            return true;
        }

        const assignments = await this.teamRoleRepository.findAssignmentsByTeamIdsAndAccountIds([teamId], [userId]);
        const roleIds = assignments.map(({roleId}) => roleId);
        const [roles, policies] = await Promise.all([
            this.teamRoleRepository.findRolePermissionsByIds(roleIds),
            this.policyRepository.findPermissionsByChannelIdAndRoleIds(channelId, roleIds),
        ]);
        return canBypassSlowmode({
            teamId,
            teamRole: member?.role,
            roles,
            channelPermissionsByRoleId: new Map(policies
                .filter(policy => policy.teamId === teamId)
                .map(policy => [policy.roleId, policy.permissions])),
        });
    }
}
