import { Controller, Get, Param, ParseIntPipe } from '@nestjs/common';
import { ApiHeader, ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
import { ChatService } from './chat.service';
import { UserId } from '../common/decorator/user.decorator';
import { UnreadCountItemDto } from './dto/unread-count-response.dto';

@ApiTags('Chat')
@ApiHeader({ name: 'X-User-Id', description: 'Gateway 자동 주입 (서비스 직접 테스트 시만 입력)', required: false })
@ApiHeader({ name: 'X-User-Role', description: 'Gateway 자동 주입 (ADMIN | MEMBER)', required: false })
@Controller('teams/:teamId')
export class TeamUnreadController {
    constructor(private readonly chatService: ChatService) {}

    @Get('unread')
    @ApiOperation({ summary: '팀 내 가입 채널별 미읽 카운트 조회' })
    @ApiResponse({ status: 200, type: [UnreadCountItemDto] })
    async getTeamUnread(
        @Param('teamId', ParseIntPipe) teamId: number,
        @UserId() userId: number,
    ): Promise<UnreadCountItemDto[]> {
        return this.chatService.getTeamUnread(teamId, userId);
    }
}
