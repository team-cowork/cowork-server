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
} from '@nestjs/swagger';
import {ApiResponseMetadataDto, apiEnvelopeResponse} from '../common/dto/api-response-metadata.dto.js';
import {requirePermission} from '../common/guard/account-permission.decorator.js';
import {MessageSearchStatusDto} from './dto/admin-response.dto.js';
import {MessageSearchAdminService} from './search/index.js';
import {StartMessageSearchRebuildDto} from './dto/index.js';

@apiTags('Chat Admin')
@apiEnvelopeResponse(400, '요청 값이 올바르지 않음')
@requirePermission('manage_chat_search_index')
@apiExtraModels(ApiResponseMetadataDto, MessageSearchStatusDto)
@apiEnvelopeResponse(401, '액세스 토큰이 없거나 유효하지 않음')
@apiResponse({status: 'default', description: '기타 오류', type: ApiResponseMetadataDto})
@apiEnvelopeResponse(429, '요청 한도 초과')
@controller('admin/message-search')
export class MessageSearchAdminController {
	constructor(private readonly messageSearchAdminService: MessageSearchAdminService) {}

	@httpGet()
	@apiOperation({summary: '메시지 검색 색인 상태 조회'})
	@apiEnvelopeResponse(200, '색인 상태와 최근 재구축 결과', MessageSearchStatusDto)
	async status() {
		return this.messageSearchAdminService.status();
	}

	@httpPost('retry-failed')
	@httpCode(HttpStatus.OK)
	@apiOperation({summary: '실패한 색인 및 삭제 작업 재시도'})
	@apiEnvelopeResponse(200, '요청 처리 성공', {type: 'object', properties: {messages: {type: 'integer'}, tombstones: {type: 'integer'}}})
	async retryFailed() {
		return this.messageSearchAdminService.retryFailed();
	}

	@httpPost('rebuild')
	@httpCode(HttpStatus.ACCEPTED)
	@apiOperation({summary: '검색 색인 재구축 요청', description: '색인 상태 조회에서 진행 상황을 확인할 수 있습니다.'})
	@apiResponse({status: 202, description: '응답 본문 없음'})
	@apiEnvelopeResponse(409, '현재 상태에서는 요청을 처리할 수 없음')
	async rebuild(@requestBody() body: StartMessageSearchRebuildDto): Promise<void> {
		if (!await this.messageSearchAdminService.startRebuild(body.resume === true)) {
			throw new ConflictException('Another search index rebuild is already running');
		}
	}
}
