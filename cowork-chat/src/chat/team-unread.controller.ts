import { Controller, Get, Param, ParseIntPipe } from '@nestjs/common';
import { ApiHeader, ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
import { ChatService } from './chat.service';
import { UserId } from '../common/decorator/user.decorator';
import { UnreadCountItemDto } from './dto/unread-count-response.dto';

@ApiTags('Chat')
@ApiHeader({ name: 'X-User-Id', description: 'Gateway 주입 유저 ID', required: true })
@ApiHeader({ name: 'X-User-Role', description: 'Gateway 주입 유저 역할 (ADMIN | MEMBER)', required: true })
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
