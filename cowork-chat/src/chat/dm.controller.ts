import {
	Controller as controller,
	Delete as httpDelete,
	Get as httpGet,
	HttpCode as httpCode,
	HttpStatus,
	Param as requestParameter,
} from '@nestjs/common';
import {
	ApiNoContentResponse as apiNoContentResponse,
	ApiOkResponse as apiOkResponse,
	ApiOperation as apiOperation,
	ApiParam as apiParameter,
	ApiTags as apiTags,
	ApiExtraModels as apiExtraModels,
	getSchemaPath,
	ApiResponse as apiResponse,
} from '@nestjs/swagger';
import {ApiResponseMetadataDto} from '../common/dto/api-response-metadata.dto.js';
import {currentUserId} from '../common/decorator/user.decorator.js';
import {SafePositiveIntPipe} from '../common/pipe/safe-positive-int.pipe.js';
import {DmConversationDto} from './dto/dm-conversation.dto.js';
import {ChatService} from './chat.service.js';

@apiTags('DM')
@apiExtraModels(ApiResponseMetadataDto, DmConversationDto)
@apiResponse({status: 401, description: '액세스 토큰이 없거나 유효하지 않음', type: ApiResponseMetadataDto})
@apiResponse({status: 'default', description: '기타 오류', type: ApiResponseMetadataDto})
@controller('dms')
export class DmController {
	constructor(private readonly chatService: ChatService) {}

	@httpGet()
	@apiOperation({summary: '내 DM 대화 목록 조회', description: '숨긴 대화를 제외하고 최근 메시지가 있는 대화부터 반환합니다.'})
	@apiOkResponse({
		description: 'DM 대화 목록',
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
						data: {type: 'array', items: {$ref: getSchemaPath(DmConversationDto)}},
					},
				},
			],
		},
	})
	async getMyDms(@currentUserId() userId: number) {
		return this.chatService.getMyDms(userId);
	}

	@httpDelete(':channelId')
	@httpCode(HttpStatus.NO_CONTENT)
	@apiOperation({summary: 'DM 대화 숨기기', description: '상대방이 메시지를 보내면 목록에 다시 표시됩니다.'})
	@apiParameter({name: 'channelId', description: '숨길 DM 채널 ID', type: Number})
	@apiNoContentResponse({description: '숨김 성공'})
	async hideDm(
		@currentUserId() userId: number,
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
	) {
		return this.chatService.hideDm(channelId, userId);
	}
}
