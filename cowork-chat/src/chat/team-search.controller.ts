import { Controller, Get, Query } from '@nestjs/common';
import { ApiHeader, ApiOperation, ApiQuery, ApiResponse, ApiTags } from '@nestjs/swagger';
import { ChatService } from './chat.service';
import { SearchTeamMessagesDto } from './dto/search-team-messages.dto';
import { SearchMessagesResponseDto } from './dto/search-message-response.dto';
import { UserId } from '../common/decorator/user.decorator';

@ApiTags('Search')
@ApiHeader({ name: 'X-User-Id', description: 'Gateway 주입 유저 ID', required: true })
@Controller('search')
export class TeamSearchController {
    constructor(private readonly chatService: ChatService) {}

    @Get('messages')
    @ApiOperation({
        summary: '팀 채팅 메시지 전체 검색',
        description:
            'Elasticsearch 기반 팀 단위 메시지 전문 검색.\n\n' +
            '- 요청자가 속한 채널 내 메시지만 반환\n' +
            '- 한국어 nori 형태소 분석 + fuzzy matching 적용\n' +
            '- 결과는 최신순 정렬 (createdAt DESC)\n' +
            '- 커서 기반 페이지네이션: 응답의 `nextCursor`를 다음 요청의 `before`에 전달',
    })
    @ApiQuery({ name: 'q', description: '검색 키워드 (최소 1자)', required: true })
    @ApiQuery({ name: 'teamId', description: '팀 ID', required: true })
    @ApiQuery({ name: 'channelId', description: '특정 채널로 범위 축소', required: false })
    @ApiQuery({ name: 'authorId', description: '특정 작성자 ID 필터', required: false })
    @ApiQuery({ name: 'type', enum: ['TEXT', 'FILE', 'SYSTEM'], description: '메시지 타입 필터', required: false })
    @ApiQuery({ name: 'hasFile', description: '첨부파일 포함 메시지만 조회 (true/false)', required: false })
    @ApiQuery({ name: 'before', description: '커서: 이전 응답의 nextCursor 값', required: false })
    @ApiQuery({ name: 'limit', description: '페이지 크기 (기본 50, 최대 100)', required: false })
    @ApiResponse({ status: 200, type: SearchMessagesResponseDto })
    @ApiResponse({ status: 403, description: '팀 멤버 아님 또는 채널 접근 권한 없음' })
    async searchTeamMessages(
        @Query() dto: SearchTeamMessagesDto,
        @UserId() userId: number,
    ): Promise<SearchMessagesResponseDto> {
        return this.chatService.searchTeamMessages(dto.teamId, dto, { userId });
    }
}
