import { Args, Context, Int, Query, Resolver } from '@nestjs/graphql';
import { Request } from 'express';
import { RequestContextUtil } from '../common/util/request-context.util';
import { UnifiedSearchResult } from '../search/unified-search.types';
import { ChatService } from './chat.service';
import { SearchTeamMessagesDto } from './dto/search-team-messages.dto';
import { ChannelSearchClient } from './service/channel-search.client';

@Resolver()
export class UnifiedSearchResolver {
    constructor(
        private readonly chatService: ChatService,
        private readonly channelSearchClient: ChannelSearchClient,
    ) {}

    @Query(() => UnifiedSearchResult, {
        description:
            '팀 범위 통합 검색. 메시지(Elasticsearch)와 채널(channel-service)을 병렬로 조회한다.\n\n' +
            '- `X-User-Id` 헤더 필수 (Gateway가 주입)\n' +
            '- 요청자가 접근 가능한 채널 내 메시지만 반환\n' +
            '- 한국어 nori 형태소 분석 + fuzzy matching 적용\n' +
            '- `messageNextCursor`를 다음 요청의 `before`에 전달해 페이지네이션',
    })
    async unifiedSearch(
        @Context('req') req: Request,
        @Args('teamId', { type: () => Int }) teamId: number,
        @Args('q') q: string,
        @Args('channelId', { type: () => Int, nullable: true }) channelId?: number,
        @Args('authorId', { type: () => Int, nullable: true }) authorId?: number,
        @Args('type', { nullable: true }) type?: string,
        @Args('hasFile', { nullable: true }) hasFile?: boolean,
        @Args('before', { nullable: true }) before?: string,
        @Args('limit', { type: () => Int, nullable: true }) limit?: number,
    ): Promise<UnifiedSearchResult> {
        const userId = RequestContextUtil.getUserId(req.headers as Record<string, string | string[] | undefined>);

        const dto = { teamId, q, channelId, authorId, type, hasFile, before, limit } as SearchTeamMessagesDto;

        const [messageResult, channels] = await Promise.all([
            this.chatService.searchTeamMessages(teamId, dto, { userId }),
            this.channelSearchClient.searchChannels(teamId, q, userId),
        ]);

        return {
            messages: messageResult.messages,
            messageNextCursor: messageResult.nextCursor,
            channels,
        };
    }
}
