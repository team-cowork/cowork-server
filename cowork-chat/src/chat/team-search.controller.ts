import {Controller as controller, Get as httpGet, Query as requestQuery} from '@nestjs/common';
import {
	ApiOperation as apiOperation,
	ApiQuery as apiQuery,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
	ApiExtraModels as apiExtraModels,
	getSchemaPath,
} from '@nestjs/swagger';
import {ApiResponseMetadataDto} from '../common/dto/api-response-metadata.dto.js';
import {currentUserId} from '../common/decorator/user.decorator.js';
import {ChatService} from './chat.service.js';
import {SearchTeamMessagesDto, SearchMessagesResponseDto} from './dto/index.js';

@apiTags('Search')
@apiExtraModels(ApiResponseMetadataDto, SearchMessagesResponseDto)
@apiResponse({status: 401, description: '액세스 토큰이 없거나 유효하지 않음', type: ApiResponseMetadataDto})
@apiResponse({status: 'default', description: '기타 오류', type: ApiResponseMetadataDto})
@controller('search')
export class TeamSearchController {
	constructor(private readonly chatService: ChatService) {}

	@httpGet('messages')
	@apiOperation({
		summary: '팀 채팅 메시지 전체 검색',
		description: '팀에서 접근할 수 있는 채널의 메시지를 검색합니다. 검색어의 오타를 허용하며 결과는 최신순입니다. '
			+ 'highlight는 일치한 부분을 <em>으로 표시합니다. 다음 페이지는 nextCursor를 before에 넣어 조회합니다.',
	})
	@apiQuery({name: 'q', description: '검색 키워드 (최소 1자)', required: true})
	@apiQuery({name: 'teamId', description: '팀 ID', required: true})
	@apiQuery({name: 'channelId', description: '특정 채널로 범위 축소', required: false})
	@apiQuery({name: 'authorId', description: '특정 작성자 ID 필터', required: false})
	@apiQuery({
		name: 'type', enum: ['TEXT', 'FILE', 'SYSTEM'], description: '메시지 타입 필터', required: false,
	})
	@apiQuery({name: 'hasFile', description: '첨부파일 포함 메시지만 조회 (true/false)', required: false})
	@apiQuery({name: 'before', description: '커서: 이전 응답의 nextCursor 값', required: false})
	@apiQuery({name: 'limit', description: '페이지 크기 (기본 50, 최대 100)', required: false})
	@apiResponse({
		status: 200,
		description: '요청 처리 완료',
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
						data: {$ref: getSchemaPath(SearchMessagesResponseDto)},
					},
				},
			],
		},
	})
	@apiResponse({
		status: 400,
		description: '검색 조건 또는 커서 형식 오류',
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
		status: 503,
		description: '검색 색인 또는 권한 정보를 사용할 수 없음',
		schema: {
			allOf: [
				{$ref: getSchemaPath(ApiResponseMetadataDto)},
				{
					type: 'object',
					properties: {status: {type: 'string', enum: ['503 SERVICE_UNAVAILABLE']}, code: {type: 'integer', enum: [503]}},
				},
			],
		},
	})
	@apiResponse({
		status: 403,
		description: '팀 멤버 아님 또는 채널 접근 권한 없음',
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
	async searchTeamMessages(
		@requestQuery() dto: SearchTeamMessagesDto,
		@currentUserId() userId: number,
	): Promise<SearchMessagesResponseDto> {
		return this.chatService.searchTeamMessages(dto.teamId, dto, {userId});
	}
}
