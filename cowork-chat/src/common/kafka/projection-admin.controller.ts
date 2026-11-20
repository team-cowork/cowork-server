import {
	Body as requestBody,
	Controller as controller,
	Get as httpGet,
	Param as requestParameter,
	Post as httpPost,
} from '@nestjs/common';
import {
	ApiExtraModels as apiExtraModels,
	ApiOperation as apiOperation,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
	getSchemaPath,
} from '@nestjs/swagger';
import {ApiResponseMetadataDto, apiEnvelopeResponse} from '../dto/api-response-metadata.dto.js';
import {requirePermission} from '../guard/account-permission.decorator.js';
import {ProjectionDatasetDto, ProjectionStreamStatusDto} from './dto/projection-status.dto.js';
import {RequestProjectionRebuildDto} from './dto/request-projection-rebuild.dto.js';
import {ProjectionReadinessService} from './projection-readiness.service.js';

/**
 전역 prefix가 붙어 서버 직접 호출 경로는 `/chat/admin/projections`이고, Gateway 경유 경로는 `/api/chat/chat/admin/projections`이다.
 `main.ts`가 `/chat/admin/` 경로를 projection readiness 검사에서 제외하므로 `REBUILD_REQUIRED`로 준비되지 않은 상태에서도 호출할 수 있다.
 계정 정책 동기화가 완료되어야 운영 권한을 확인할 수 있다.
 */
@apiTags('Projection Admin')
@requirePermission('view_chat_projections')
@apiExtraModels(ApiResponseMetadataDto, ProjectionDatasetDto, ProjectionStreamStatusDto)
@apiEnvelopeResponse(401, '액세스 토큰이 없거나 유효하지 않음')
@apiResponse({status: 'default', description: '기타 오류', type: ApiResponseMetadataDto})
@apiEnvelopeResponse(429, '요청 한도 초과')
@controller('admin/projections')
export class ProjectionAdminController {
	constructor(private readonly readiness: ProjectionReadinessService) {}

	@httpGet()
	@apiOperation({summary: '스트림별 동기화 상태 조회'})
	@apiEnvelopeResponse(200, '스트림 이름별 동기화 상태', {type: 'object', additionalProperties: {$ref: getSchemaPath(ProjectionStreamStatusDto)}})
	status() {
		return this.readiness.getDetailedStatus();
	}

	@requirePermission('rebuild_chat_projections')
	@httpPost(':stream/rebuild')
	@apiOperation({summary: '스트림 재구축 요청'})
	@apiEnvelopeResponse(201, '재구축 요청을 반영한 상태', ProjectionDatasetDto)
	@apiEnvelopeResponse(400, '스트림이 없거나 재구축 사유가 올바르지 않음')
	async rebuild(@requestParameter('stream') stream: string, @requestBody() body: RequestProjectionRebuildDto) {
		return this.readiness.requestRebuild(stream, body.reason);
	}
}
