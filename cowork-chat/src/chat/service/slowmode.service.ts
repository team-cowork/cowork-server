import {
	HttpException,
	HttpStatus,
	Injectable as injectable,
	Inject as inject,
} from '@nestjs/common';
import {RedisRateLimiter} from '../../common/util/redis-rate-limiter.js';
import {
	ChannelAccessProjectionRepository,
	type ChannelAccessProjectionView,
	TeamMemberProjectionRepository,
	type TeamMemberProjectionView,
	TextChannelSettingProjectionRepository,
} from '../repository/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {isCurrentChannelAccessDecision} from './channel-message-read-access.service.js';

export const SLOWMODE_BYPASS_PERMISSION = 'slowmode_bypass';

export type SlowmodeReservation = {
	key: string;
	holder: string;
};

/**
Only active members can bypass. Preference's current channel access decision must allow `slowmode_bypass`.
*/
export function canBypassSlowmode(
	decision: ChannelAccessProjectionView | undefined,
	member: Pick<TeamMemberProjectionView, 'teamId' | 'authorizationVersion'> | undefined,
): boolean {
	return isCurrentChannelAccessDecision(decision, member)
		&& decision.allowedPermissions.includes(SLOWMODE_BYPASS_PERMISSION);
}

@injectable()
export class SlowmodeService {
	@inject()
	private readonly settingRepository!: TextChannelSettingProjectionRepository;

	@inject()
	private readonly teamMemberRepository!: TeamMemberProjectionRepository;

	@inject()
	private readonly channelAccessRepository!: ChannelAccessProjectionRepository;

	@inject()
	private readonly rateLimiter!: RedisRateLimiter;

	private async canBypass(teamId: number, channelId: number, userId: number): Promise<boolean> {
		const [[member], [decision]] = await Promise.all([
			this.teamMemberRepository.findByTeamIdsAndUserIds([teamId], [userId]),
			this.channelAccessRepository.findByChannelIdsAndUserIds([channelId], [userId]),
		]);
		return canBypassSlowmode(decision, member);
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
