import {Controller as controller, Get as httpGet, Param as requestParameter} from '@nestjs/common';
import {
	ApiOperation as apiOperation,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
	ApiExtraModels as apiExtraModels,
	getSchemaPath,
} from '@nestjs/swagger';
import {ApiResponseMetadataDto, apiEnvelopeResponse} from '../common/dto/api-response-metadata.dto.js';
import {currentUserId} from '../common/decorator/user.decorator.js';
import {SafePositiveIntPipe} from '../common/pipe/safe-positive-int.pipe.js';
import {ChatService} from './chat.service.js';
import {UnreadCountItemDto} from './dto/index.js';

@apiTags('Chat')
@apiExtraModels(ApiResponseMetadataDto, UnreadCountItemDto)
@apiEnvelopeResponse(401, '액세스 토큰이 없거나 유효하지 않음')
@apiResponse({status: 'default', description: '기타 오류', type: ApiResponseMetadataDto})
@apiEnvelopeResponse(429, '요청 한도 초과')
@controller('teams/:teamId')
export class TeamUnreadController {
	constructor(private readonly chatService: ChatService) {}

	@httpGet('unread')
	@apiOperation({summary: '팀 채널별 읽지 않은 메시지 수 조회'})
	@apiEnvelopeResponse(200, '요청 처리 성공', {type: 'array', items: {$ref: getSchemaPath(UnreadCountItemDto)}})
	@apiEnvelopeResponse(400, '팀 ID 형식 오류')
	@apiEnvelopeResponse(403, '팀 접근 권한 없음')
	@apiEnvelopeResponse(503, '권한 정보 동기화 중')
	async getTeamUnread(
		@requestParameter('teamId', SafePositiveIntPipe) teamId: number,
		@currentUserId() userId: number,
	): Promise<UnreadCountItemDto[]> {
		return this.chatService.getTeamUnread(teamId, userId);
	}
}
