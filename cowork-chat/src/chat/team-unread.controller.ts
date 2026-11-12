import {Controller as controller, Get as httpGet, Param as requestParameter} from '@nestjs/common';
import {
	ApiOperation as apiOperation,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
	ApiExtraModels as apiExtraModels,
	getSchemaPath,
} from '@nestjs/swagger';
import {ApiResponseMetadataDto} from '../common/dto/api-response-metadata.dto.js';
import {currentUserId} from '../common/decorator/user.decorator.js';
import {SafePositiveIntPipe} from '../common/pipe/safe-positive-int.pipe.js';
import {ChatService} from './chat.service.js';
import {UnreadCountItemDto} from './dto/index.js';

@apiTags('Chat')
@apiExtraModels(ApiResponseMetadataDto, UnreadCountItemDto)
@apiResponse({status: 401, description: '액세스 토큰이 없거나 유효하지 않음', type: ApiResponseMetadataDto})
@apiResponse({status: 'default', description: '기타 오류', type: ApiResponseMetadataDto})
@apiResponse({status: 429, description: '요청 한도 초과', type: ApiResponseMetadataDto})
@controller('teams/:teamId')
export class TeamUnreadController {
	constructor(private readonly chatService: ChatService) {}

	@httpGet('unread')
	@apiOperation({summary: '팀 채널별 읽지 않은 메시지 수 조회'})
	@apiResponse({
		status: 200,
		description: '요청 처리 성공',
		schema: {
			allOf: [
				{$ref: getSchemaPath(ApiResponseMetadataDto)},
				{
					type: 'object',
					required: ['data'],
					properties: {
						status: {type: 'string', enum: ['200 OK']},
						code: {type: 'integer', enum: [200]},
						message: {type: 'string', example: 'OK'},
						data: {type: 'array', items: {$ref: getSchemaPath(UnreadCountItemDto)}},
					},
				},
			],
		},
	})
	@apiResponse({status: 400, description: '팀 ID 형식 오류', type: ApiResponseMetadataDto})
	@apiResponse({status: 403, description: '팀 접근 권한 없음', type: ApiResponseMetadataDto})
	@apiResponse({status: 503, description: '권한 정보 동기화 중', type: ApiResponseMetadataDto})
	async getTeamUnread(
		@requestParameter('teamId', SafePositiveIntPipe) teamId: number,
		@currentUserId() userId: number,
	): Promise<UnreadCountItemDto[]> {
		return this.chatService.getTeamUnread(teamId, userId);
	}
}
