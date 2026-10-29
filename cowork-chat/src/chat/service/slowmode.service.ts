import {
	HttpException,
	HttpStatus,
	Injectable as injectable,
	Inject as inject,
} from '@nestjs/common';
import {RedisRateLimiter} from '../../common/util/redis-rate-limiter.js';
import {
	ChannelRolePolicyProjectionRepository,
	TeamMemberProjectionRepository,
	TeamRoleProjectionRepository,
	TextChannelSettingProjectionRepository,
} from '../repository/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {hasChannelPermission} from './channel-permission.js';

export const SLOWMODE_BYPASS_PERMISSION = 'slowmode_bypass';
const BYPASS_TEAM_ROLES = new Set(['OWNER', 'ADMIN']);

export type SlowmodeReservation = {
	key: string;
	holder: string;
};

export type SlowmodeBypassInput = {
	teamId: number;
	teamRole: string | undefined;
	roles: Array<{roleId: number; teamId: number; priority: number; isDefault?: boolean; permissions: Record<string, boolean>}>;
	channelPermissionsByRoleId: Map<number, Record<string, boolean>>;
};

/**
Only active members can bypass; role and channel grants follow the shared permission rules.
*/
export function canBypassSlowmode(input: SlowmodeBypassInput): boolean {
	if (input.teamRole === undefined) {
		return false;
	}

	if (BYPASS_TEAM_ROLES.has(input.teamRole)) {
		return true;
	}

	return hasChannelPermission(
		input.roles.filter(role => role.teamId === input.teamId),
		input.channelPermissionsByRoleId,
		SLOWMODE_BYPASS_PERMISSION,
	);
}

@injectable()
export class SlowmodeService {
	@inject()
	private readonly settingRepository!: TextChannelSettingProjectionRepository;

	@inject()
	private readonly teamMemberRepository!: TeamMemberProjectionRepository;

	@inject()
	private readonly teamRoleRepository!: TeamRoleProjectionRepository;

	@inject()
	private readonly policyRepository!: ChannelRolePolicyProjectionRepository;

	@inject()
	private readonly rateLimiter!: RedisRateLimiter;

	private async canBypass(teamId: number, channelId: number, userId: number): Promise<boolean> {
		const [member] = await this.teamMemberRepository.findByTeamIdsAndUserIds([teamId], [userId]);
		if (member !== undefined && BYPASS_TEAM_ROLES.has(member.role)) {
			return true;
		}

		if (member === undefined) {
			return false;
		}

		const [assignments, defaults] = await Promise.all([
			this.teamRoleRepository.findAssignmentsByTeamIdsAndAccountIds([teamId], [userId]),
			this.teamRoleRepository.findDefaultRolesByTeamIds([teamId]),
		]);
		const roleIds = [...new Set([...assignments, ...defaults].map(({roleId}) => roleId))];
		const [roles, policies] = await Promise.all([
			this.teamRoleRepository.findRolePermissionsByIds(roleIds),
			this.policyRepository.findPermissionsByChannelIdAndRoleIds(channelId, roleIds),
		]);
		return canBypassSlowmode({
			teamId,
			teamRole: member?.role,
			roles: [...roles, ...defaults.map(role => ({...role, permissions: role.permissions ?? {}}))],
			channelPermissionsByRoleId: new Map(policies
				.filter(policy => policy.teamId === teamId)
				.map(policy => [policy.roleId, policy.permissions])),
		});
	}

	/**
     슬로우 모드 간격을 `clientMessageId`로 예약하고, 전송이 실패하면 {@link release}에 넘길 예약을 돌려준다.
     예약할 필요가 없거나 같은 `clientMessageId`의 재시도면 `undefined`다.

     @throws HttpException 429 간격 안에 다른 메시지를 보낸 경우
     */
	async reserve(
		channelId: number,
		teamId: Nullable<number | undefined>,
		userId: number,
		clientMessageId: string,
	): Promise<SlowmodeReservation | undefined> {
		if (teamId === null || teamId === undefined) {
			return undefined;
		}

		const slowmodeSeconds = await this.settingRepository.findSlowmodeSeconds(channelId);
		if (slowmodeSeconds <= 0 || await this.canBypass(teamId, channelId, userId)) {
			return undefined;
		}

		const key = `chat:slowmode:${channelId}:${userId}`;
		const result = await this.rateLimiter.tryReserve(key, clientMessageId, slowmodeSeconds * 1000);
		if (result === 'BUSY') {
			throw new HttpException(
				'슬로우 모드가 켜진 채널입니다. 잠시 후 다시 시도하십시오.',
				HttpStatus.TOO_MANY_REQUESTS,
			);
		}

		return result === 'RESERVED' ? {key, holder: clientMessageId} : undefined;
	}

	async release(reservation: SlowmodeReservation | undefined): Promise<void> {
		if (reservation !== undefined) {
			await this.rateLimiter.release(reservation.key, reservation.holder);
		}
	}
}
