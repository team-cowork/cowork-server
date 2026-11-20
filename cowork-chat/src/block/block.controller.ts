import {
	Controller as controller,
	Delete as httpDelete,
	Get as httpGet,
	HttpCode as httpCode,
	HttpStatus,
	Param as requestParameter,
	Post as httpPost,
} from '@nestjs/common';
import {
	ApiNoContentResponse as apiNoContentResponse,
	ApiOkResponse as apiOkResponse,
	ApiOperation as apiOperation,
	ApiParam as apiParameter,
	ApiTags as apiTags,
	ApiExtraModels as apiExtraModels,
	ApiResponse as apiResponse,
} from '@nestjs/swagger';
import {ApiResponseMetadataDto, apiEnvelopeResponse} from '../common/dto/api-response-metadata.dto.js';
import {currentUserId} from '../common/decorator/user.decorator.js';
import {SafePositiveIntPipe} from '../common/pipe/safe-positive-int.pipe.js';
import {BlockService} from './block.service.js';

@apiTags('block')
@apiExtraModels(ApiResponseMetadataDto)
@apiEnvelopeResponse(401, '액세스 토큰이 없거나 유효하지 않음')
@apiResponse({status: 'default', description: '기타 오류', type: ApiResponseMetadataDto})
@apiEnvelopeResponse(429, '요청 한도 초과')
@controller('block')
export class BlockController {
	constructor(private readonly blockService: BlockService) {}

	@httpPost(':targetUserId')
	@httpCode(HttpStatus.NO_CONTENT)
	@apiOperation({summary: '사용자 차단', description: '차단한 사용자의 DM을 받지 않습니다.'})
	@apiParameter({name: 'targetUserId', description: '차단할 사용자 ID', type: Number})
	@apiNoContentResponse({description: '차단 성공'})
	@apiEnvelopeResponse(409, '차단 목록이 상한에 도달함')
	@apiEnvelopeResponse(400, '사용자 ID 형식 오류')
	async blockUser(
		@currentUserId() userId: number,
		@requestParameter('targetUserId', SafePositiveIntPipe) targetUserId: number,
	) {
		return this.blockService.blockUser(userId, targetUserId);
	}

	@httpDelete(':targetUserId')
	@httpCode(HttpStatus.NO_CONTENT)
	@apiOperation({summary: '차단 해제'})
	@apiParameter({name: 'targetUserId', description: '차단 해제할 사용자 ID', type: Number})
	@apiNoContentResponse({description: '차단 해제 성공'})
	@apiEnvelopeResponse(400, '사용자 ID 형식 오류')
	async unblockUser(
		@currentUserId() userId: number,
		@requestParameter('targetUserId', SafePositiveIntPipe) targetUserId: number,
	) {
		return this.blockService.unblockUser(userId, targetUserId);
	}

	@httpGet()
	@apiOperation({summary: '차단 목록 조회'})
	@apiEnvelopeResponse(200, '요청 처리 성공', {type: 'array', items: {type: 'number'}, example: [2, 5, 13]})
	async getBlockedUsers(@currentUserId() userId: number) {
		return this.blockService.getBlockedUsers(userId);
	}
}
