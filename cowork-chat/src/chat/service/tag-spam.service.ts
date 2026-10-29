import {
	BadRequestException,
	HttpException,
	HttpStatus,
	Injectable as injectable,
	Inject as inject,
} from '@nestjs/common';
import {RedisRateLimiter} from '../../common/util/redis-rate-limiter.js';
import {TeamMemberProjectionRepository, TeamSettingProjectionRepository} from '../repository/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {parseMentions} from '../util/index.js';

export const MAX_MENTIONS_PER_MESSAGE = 10;
export const MAX_MENTIONS_PER_WINDOW = 20;
export const MENTION_WINDOW_MS = 60_000;
const BYPASS_TEAM_ROLES = new Set(['OWNER', 'ADMIN']);

export type TagSpamReservation = {
	key: string;
	holder: string;
	weight: number;
};

export type TagSpamDecision = 'SKIP' | 'MESSAGE_LIMIT_EXCEEDED' | 'CHECK_WINDOW';

/**
차단이 꺼졌거나 OWNER·ADMIN이면 검사하지 않는다. 멘션 수는 메시지 안에서 중복을 뺀 사용자 수다.
*/
export function decideTagSpam(input: {
	tagSpamBlock: boolean;
	teamRole: string | undefined;
	mentionCount: number;
}): TagSpamDecision {
	if (!input.tagSpamBlock || input.mentionCount === 0
		|| (input.teamRole !== undefined && BYPASS_TEAM_ROLES.has(input.teamRole))) {
		return 'SKIP';
	}

	return input.mentionCount > MAX_MENTIONS_PER_MESSAGE ? 'MESSAGE_LIMIT_EXCEEDED' : 'CHECK_WINDOW';
}

@injectable()
export class TagSpamService {
	@inject()
	private readonly settingRepository!: TeamSettingProjectionRepository;

	@inject()
	private readonly teamMemberRepository!: TeamMemberProjectionRepository;

	@inject()
	private readonly rateLimiter!: RedisRateLimiter;

	/**
     태그 스팸 차단이 켜진 팀에서 멘션 수를 `clientMessageId`로 예약하고, 전송이 실패하면 {@link release}에 넘길 예약을 돌려준다.
     예약할 필요가 없거나 같은 `clientMessageId`의 재시도면 `undefined`다.

     @throws BadRequestException 메시지 하나의 멘션 수가 상한을 넘은 경우
     @throws HttpException 429 시간창 안의 멘션 수 합계가 상한을 넘은 경우
     */
	async reserve(
		teamId: Nullable<number | undefined>,
		userId: number,
		content: string,
		clientMessageId: string,
	): Promise<TagSpamReservation | undefined> {
		if (teamId === null || teamId === undefined) {
			return undefined;
		}

		const mentionCount = parseMentions(content).length;
		if (mentionCount === 0 || !await this.settingRepository.isTagSpamBlockEnabled(teamId)) {
			return undefined;
		}

		const [member] = await this.teamMemberRepository.findByTeamIdsAndUserIds([teamId], [userId]);
		const decision = decideTagSpam({tagSpamBlock: true, teamRole: member?.role, mentionCount});
		if (decision === 'SKIP') {
			return undefined;
		}

		if (decision === 'MESSAGE_LIMIT_EXCEEDED') {
			throw new BadRequestException('메시지 하나에 멘션할 수 있는 인원을 넘었습니다.');
		}

		const key = `chat:mentionrate:${teamId}:${userId}`;
		const result = await this.rateLimiter.tryReserveWeighted(
			key, clientMessageId, mentionCount, MENTION_WINDOW_MS, MAX_MENTIONS_PER_WINDOW,
		);
		if (result === 'BUSY') {
			throw new HttpException(
				'짧은 시간에 너무 많은 멘션을 보냈습니다. 잠시 후 다시 시도하십시오.',
				HttpStatus.TOO_MANY_REQUESTS,
			);
		}

		return result === 'RESERVED' ? {key, holder: clientMessageId, weight: mentionCount} : undefined;
	}

	async release(reservation: TagSpamReservation | undefined): Promise<void> {
		if (reservation !== undefined) {
			await this.rateLimiter.releaseWeighted(reservation.key, reservation.holder, reservation.weight);
		}
	}
}
