import {
	ConflictException,
	Controller as controller,
	Get as httpGet,
	HttpCode as httpCode,
	HttpStatus,
	Param as requestParameter,
	Post as httpPost,
	Query as requestQuery,
} from '@nestjs/common';
import {
	ApiOperation as apiOperation,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
	ApiExtraModels as apiExtraModels,
	getSchemaPath,
} from '@nestjs/swagger';
import {ApiResponseMetadataDto, apiResponseEnvelope} from '../common/dto/api-response-metadata.dto.js';
import {UserRole} from '../common/enum/user-role.enum.js';
import {requireRoles} from '../common/guard/roles.decorator.js';
import {QuarantineRecordDto} from './dto/admin-response.dto.js';
import {ChatMessageQuarantineService} from './service/index.js';
import {ChatMessageQuarantineRecordIdDto, FindChatMessageQuarantineRecordsDto} from './dto/index.js';

@apiTags('Chat Admin')
@apiResponse({
	status: 400,
	description: '레코드 ID 또는 상태 값이 올바르지 않음',
	schema: apiResponseEnvelope(400),
})
@apiResponse({
	status: 403,
	description: 'ADMIN 권한 필요',
	schema: apiResponseEnvelope(403),
})
@requireRoles(UserRole.ADMIN)
@apiExtraModels(ApiResponseMetadataDto, QuarantineRecordDto)
@apiResponse({status: 401, description: '액세스 토큰이 없거나 유효하지 않음', type: ApiResponseMetadataDto})
@apiResponse({status: 'default', description: '기타 오류', type: ApiResponseMetadataDto})
@apiResponse({status: 429, description: '요청 한도 초과', type: ApiResponseMetadataDto})
@controller('admin/message-quarantine')
export class ChatMessageQuarantineAdminController {
	constructor(private readonly quarantineService: ChatMessageQuarantineService) {}

	@httpGet()
	@apiOperation({summary: '격리된 메시지의 상태 및 원인별 건수 조회'})
	@apiResponse({
		status: 200,
		description: '요청 처리 성공',
		schema: apiResponseEnvelope(200, {
			type: 'array',
			items: {
				type: 'object',
				properties: {
					status: {type: 'string'},
					errorType: {type: 'string'},
					reasonCode: {type: 'string'},
					count: {type: 'integer'},
				},
			},
		}),
	})
	async summary() {
		return this.quarantineService.summary();
	}

	@httpGet('records')
	@apiOperation({summary: '최근 격리 레코드 조회', description: '최근 관측 순서로 최대 100건을 반환합니다. payload와 eventKey는 반환하지 않습니다.'})
	@apiResponse({
		status: 200,
		description: '최근 격리 레코드 목록',
		schema: apiResponseEnvelope(200, {type: 'array', items: {$ref: getSchemaPath(QuarantineRecordDto)}}),
	})
	async records(@requestQuery() query: FindChatMessageQuarantineRecordsDto) {
		return this.quarantineService.findRecent(query.status);
	}

	@httpPost(':id/reprocess')
	@httpCode(HttpStatus.NO_CONTENT)
	@apiOperation({summary: '격리 레코드 재처리 요청'})
	@apiResponse({status: 204, description: '응답 본문 없음'})
	@apiResponse({
		status: 409,
		description: '현재 상태에서는 요청을 처리할 수 없음',
		schema: apiResponseEnvelope(409),
	})
	async reprocess(@requestParameter() path: ChatMessageQuarantineRecordIdDto): Promise<void> {
		if (!await this.quarantineService.requestReprocess(path.id)) {
			throw new ConflictException('Quarantine record cannot be reprocessed in its current state');
		}
	}

	@httpPost(':id/discard')
	@httpCode(HttpStatus.NO_CONTENT)
	@apiOperation({summary: '격리 레코드 폐기'})
	@apiResponse({status: 204, description: '응답 본문 없음'})
	@apiResponse({
		status: 409,
		description: '현재 상태에서는 요청을 처리할 수 없음',
		schema: apiResponseEnvelope(409),
	})
	async discard(@requestParameter() path: ChatMessageQuarantineRecordIdDto): Promise<void> {
		if (!await this.quarantineService.discard(path.id)) {
			throw new ConflictException('Quarantine record cannot be discarded in its current state');
		}
	}
}
