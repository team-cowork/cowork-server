import {ForbiddenException, Injectable as injectable} from '@nestjs/common';
import {ChannelProjectionRepository, TeamMemberProjectionRepository} from '../repository/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {ChannelMessageReadAccessService} from './channel-message-read-access.service.js';

export type ChannelSearchItem = {
	id: number;
	name: string;
	type: string;
	viewType: string;
	description: Nullable<string>;
	isPrivate: boolean;
};

/**
Kafka로 동기화된 로컬 채널 projection 검색기.
*/
@injectable()
export class ChannelSearchClient {
	constructor(
		private readonly channelRepository: ChannelProjectionRepository,
		private readonly memberRepository: TeamMemberProjectionRepository,
		private readonly accessService: ChannelMessageReadAccessService,
	) {}

	async searchChannels(teamId: number, query: string, userId: number): Promise<ChannelSearchItem[]> {
		if (!(await this.memberRepository.exists(teamId, userId))) {
			throw new ForbiddenException('팀 접근 권한이 없습니다');
		}

		if (query.trim().length === 0) {
			return [];
		}

		const channels = await this.channelRepository.searchByTeamAndName(teamId, query);
		const visibleChannelIds = new Set(await this.accessService.filterVisibleChannelIds(
			teamId,
			userId,
			channels.map(channel => channel.channelId),
		));
		return channels.filter(channel => visibleChannelIds.has(channel.channelId)).map(channel => ({
			id: channel.channelId,
			name: channel.name,
			type: channel.type,
			viewType: channel.viewType,
			description: channel.description,
			isPrivate: channel.isPrivate,
		}));
	}
}
