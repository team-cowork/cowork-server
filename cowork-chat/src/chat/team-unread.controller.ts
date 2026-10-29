import {Controller as controller, Get as httpGet, Param as requestParameter} from '@nestjs/common';
import {
	ApiHeader as apiHeader,
	ApiOperation as apiOperation,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
} from '@nestjs/swagger';
import {currentUserId} from '../common/decorator/user.decorator.js';
import {SafePositiveIntPipe} from '../common/pipe/safe-positive-int.pipe.js';
import {ChatService} from './chat.service.js';
import {UnreadCountItemDto} from './dto/index.js';

@apiTags('Chat')
@apiHeader({name: 'X-User-Id', description: 'Gateway 자동 주입 (서비스 직접 테스트 시만 입력)', required: false})
@apiHeader({name: 'X-User-Role', description: 'Gateway 자동 주입 (ADMIN | MEMBER)', required: false})
@controller('teams/:teamId')
export class TeamUnreadController {
	constructor(private readonly chatService: ChatService) {}

	@httpGet('unread')
	@apiOperation({summary: '팀 내 가입 채널별 미읽 카운트 조회'})
	@apiResponse({status: 200, type: [UnreadCountItemDto]})
	async getTeamUnread(
		@requestParameter('teamId', SafePositiveIntPipe) teamId: number,
		@currentUserId() userId: number,
	): Promise<UnreadCountItemDto[]> {
		return this.chatService.getTeamUnread(teamId, userId);
	}
}
