import {
	Body as requestBody,
	ConflictException,
	Controller as controller,
	Get as httpGet,
	HttpCode as httpCode,
	HttpStatus,
	Post as httpPost,
} from '@nestjs/common';
import {
	ApiOperation as apiOperation,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
	ApiExtraModels as apiExtraModels,
	getSchemaPath,
} from '@nestjs/swagger';
import {ApiResponseMetadataDto} from '../common/dto/api-response-metadata.dto.js';
import {UserRole} from '../common/enum/user-role.enum.js';
import {requireRoles} from '../common/guard/roles.decorator.js';
import {MessageSearchStatusDto} from './dto/admin-response.dto.js';
import {MessageSearchAdminService} from './search/index.js';
import {StartMessageSearchRebuildDto} from './dto/index.js';

@apiTags('Chat Admin')
@apiResponse({
	status: 400,
	description: '요청 값이 올바르지 않음',
	schema: {
		allOf: [
			{$ref: getSchemaPath(ApiResponseMetadataDto)},
			{
				type: 'object',
				properties: {status: {type: 'string', enum: ['400 BAD_REQUEST']}, code: {type: 'integer', enum: [400]}},
			},
		],
	},
})
@apiResponse({
	status: 403,
	description: 'ADMIN 권한 필요',
	schema: {
		allOf: [
			{$ref: getSchemaPath(ApiResponseMetadataDto)},
			{
				type: 'object',
				properties: {status: {type: 'string', enum: ['403 FORBIDDEN']}, code: {type: 'integer', enum: [403]}},
			},
		],
	},
})
@requireRoles(UserRole.ADMIN)
@apiExtraModels(ApiResponseMetadataDto, MessageSearchStatusDto)
@apiResponse({status: 401, description: '액세스 토큰이 없거나 유효하지 않음', type: ApiResponseMetadataDto})
@apiResponse({status: 'default', description: '기타 오류', type: ApiResponseMetadataDto})
@apiResponse({status: 429, description: '요청 한도 초과', type: ApiResponseMetadataDto})
@controller('admin/message-search')
export class MessageSearchAdminController {
	constructor(private readonly messageSearchAdminService: MessageSearchAdminService) {}

	@httpGet()
	@apiOperation({summary: '메시지 검색 색인 상태 조회'})
	@apiResponse({
		status: 200,
		description: '색인 상태와 최근 재구축 결과',
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
						data: {$ref: getSchemaPath(MessageSearchStatusDto)},
					},
				},
			],
		},
	})
	async status() {
		return this.messageSearchAdminService.status();
	}

	@httpPost('retry-failed')
	@httpCode(HttpStatus.OK)
	@apiOperation({summary: '실패한 색인 및 삭제 작업 재시도'})
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
						data: {type: 'object', properties: {messages: {type: 'integer'}, tombstones: {type: 'integer'}}},
					},
				},
			],
		},
	})
	async retryFailed() {
		return this.messageSearchAdminService.retryFailed();
	}

	@httpPost('rebuild')
	@httpCode(HttpStatus.ACCEPTED)
	@apiOperation({summary: '검색 색인 재구축 요청', description: '색인 상태 조회에서 진행 상황을 확인할 수 있습니다.'})
	@apiResponse({status: 202, description: '응답 본문 없음'})
	@apiResponse({
		status: 409,
		description: '현재 상태에서는 요청을 처리할 수 없음',
		schema: {
			allOf: [
				{$ref: getSchemaPath(ApiResponseMetadataDto)},
				{
					type: 'object',
					properties: {status: {type: 'string', enum: ['409 CONFLICT']}, code: {type: 'integer', enum: [409]}},
				},
			],
		},
	})
	async rebuild(@requestBody() body: StartMessageSearchRebuildDto): Promise<void> {
		if (!await this.messageSearchAdminService.startRebuild(body.resume === true)) {
			throw new ConflictException('Another search index rebuild is already running');
		}
	}
}
