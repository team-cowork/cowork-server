import {
    Controller,
    Get,
    Param,
    ParseIntPipe,
    Query,
} from '@nestjs/common';
import { ApiHeader, ApiOperation, ApiQuery, ApiResponse, ApiTags } from '@nestjs/swagger';
import { ChatService } from './chat.service';
import { SearchMessagesDto } from './dto/search-messages.dto';
import { SearchMessagesResponseDto } from './dto/search-message-response.dto';
import { UserId } from '../common/decorator/user.decorator';

@ApiTags('Chat')
@ApiHeader({ name: 'X-User-Id', description: 'Gateway 주입 유저 ID', required: true })
@Controller('projects/:projectId')
export class ProjectMessageController {
    constructor(private readonly chatService: ChatService) {}

    @Get('messages/search')
    @ApiOperation({
        summary: '프로젝트 채팅 메시지 검색',
        description:
            'Elasticsearch 기반 전문 검색. 프로젝트에 속한 채널 중 요청자가 접근 가능한 채널에서 검색.\n\n' +
            '- 한국어 nori 형태소 분석 적용\n' +
            '- fuzzy matching으로 오타 허용\n' +
            '- 검색 키워드는 응답의 `highlight` 필드에 `<em>` 태그로 표시\n' +
            '- 결과는 최신순 정렬 (createdAt DESC)\n' +
            '- 커서 기반 페이지네이션: 응답의 `nextCursor`를 다음 요청의 `before`에 전달',
    })
    @ApiQuery({ name: 'q', description: '검색 키워드 (최소 1자)', required: true })
    @ApiQuery({ name: 'channelId', description: '특정 채널로 범위 축소', required: false })
    @ApiQuery({ name: 'authorId', description: '특정 작성자 ID 필터', required: false })
    @ApiQuery({ name: 'type', enum: ['TEXT', 'FILE', 'SYSTEM'], description: '메시지 타입 필터', required: false })
    @ApiQuery({ name: 'hasFile', description: '첨부파일 포함 메시지만 조회 (true/false)', required: false })
    @ApiQuery({ name: 'before', description: '커서: 이전 응답의 nextCursor 값', required: false })
    @ApiQuery({ name: 'limit', description: '페이지 크기 (기본 50, 최대 100)', required: false })
    @ApiResponse({ status: 200, type: SearchMessagesResponseDto })
    @ApiResponse({ status: 403, description: '프로젝트 멤버 아님 또는 채널 접근 권한 없음' })
    async searchMessages(
        @Param('projectId', ParseIntPipe) projectId: number,
        @Query() dto: SearchMessagesDto,
        @UserId() userId: number,
    ): Promise<SearchMessagesResponseDto> {
        return this.chatService.searchProjectMessages(projectId, dto, userId);
    }
}
