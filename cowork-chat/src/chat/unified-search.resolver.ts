import {
	Args as graphqlArgs,
	Context as graphqlContext,
	Query as graphqlQuery,
	Resolver as resolver,
} from '@nestjs/graphql';
import {BadRequestException} from '@nestjs/common';
import {Request} from 'express';
import {plainToInstance} from 'class-transformer';
import {validate} from 'class-validator';
import {requestContextUtil} from '../common/util/request-context.util.js';
import {UnifiedSearchArgs, UnifiedSearchResult} from '../search/unified-search.types.js';
import {ChatService} from './chat.service.js';
import {SearchTeamMessagesDto} from './dto/index.js';
import {ChannelSearchClient} from './service/index.js';

@resolver()
export class UnifiedSearchResolver {
	constructor(
		private readonly chatService: ChatService,
		private readonly channelSearchClient: ChannelSearchClient,
	) {}

	@graphqlQuery(() => UnifiedSearchResult, {
		description:
            '팀 범위 통합 검색. 메시지(Elasticsearch)와 Kafka 동기화 채널 projection을 병렬로 조회한다.\n\n'
            + '- `X-User-Id` 헤더 필수 (Gateway가 주입)\n'
            + '- 요청자가 접근 가능한 채널 내 메시지만 반환\n'
            + '- 채널은 공개 채널과 요청자가 가입한 비공개 채널만 반환\n'
            + '- 한국어 nori 형태소 분석 + fuzzy matching 적용\n'
            + '- `messageNextCursor`를 다음 요청의 `before`에 전달해 페이지네이션',
	})
	async unifiedSearch(
		@graphqlContext('req') request: Request,
		@graphqlArgs() args: UnifiedSearchArgs,
	): Promise<UnifiedSearchResult> {
		const {
			teamId, q, channelId, authorId, type, hasFile, before, limit,
		} = args;
		const userId = requestContextUtil.getUserId(request.headers);

		const dto = plainToInstance(SearchTeamMessagesDto, {
			teamId, q, channelId, authorId, type, hasFile, before, limit,
		});
		const errors = await validate(dto);
		if (errors.length > 0) {
			throw new BadRequestException('유효하지 않은 검색 요청입니다');
		}

		const [messageResult, channels] = await Promise.all([
			this.chatService.searchTeamMessages(teamId, dto, {userId}),
			this.channelSearchClient.searchChannels(teamId, q, userId),
		]);

		return {
			messages: messageResult.messages,
			messageNextCursor: messageResult.nextCursor,
			channels,
		};
	}
}
