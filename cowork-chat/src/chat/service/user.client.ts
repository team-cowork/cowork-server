import {Injectable as injectable} from '@nestjs/common';
import {TeamMemberProjectionRepository, UserProfileProjectionRepository} from '../repository/index.js';
import {type Nullable} from '../../common/type/nullable.js';

/**
Kafka로 동기화된 로컬 사용자 프로필 projection 조회기.
*/
@injectable()
export class UserClient {
	constructor(
		private readonly projectionRepository: UserProfileProjectionRepository,
		private readonly teamMemberRepository: TeamMemberProjectionRepository,
	) {}

	/**
     팀 컨텍스트가 있으면 팀 닉네임, 계정 닉네임, 계정 이름 순으로 표시 이름을 고른다.
     팀 닉네임은 `team.member.event` projection에서 읽는다.
     */
	async getDisplayNames(userIds: number[], teamId?: Nullable<number>): Promise<Map<number, string>> {
		const uniqueUserIds = [...new Set(userIds)];
		if (uniqueUserIds.length === 0) {
			return new Map();
		}

		const [profiles, teamNicknames] = await Promise.all([
			this.projectionRepository.findByUserIds(uniqueUserIds),
			teamId === null || teamId === undefined ? new Map<number, string>() : this.teamMemberRepository.findNicknames(teamId, uniqueUserIds),
		]);
		const names = new Map(profiles.map(profile => [
			profile.userId,
			(profile.nickname !== null && profile.nickname !== undefined) && profile.nickname.length > 0 ? profile.nickname : profile.name,
		]));
		for (const [userId, nickname] of teamNicknames) {
			names.set(userId, nickname);
		}

		return names;
	}
}
