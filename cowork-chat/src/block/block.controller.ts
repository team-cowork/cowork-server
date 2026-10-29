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
} from '@nestjs/swagger';
import {currentUserId} from '../common/decorator/user.decorator.js';
import {SafePositiveIntPipe} from '../common/pipe/safe-positive-int.pipe.js';
import {BlockService} from './block.service.js';

@apiTags('block')
@controller('block')
export class BlockController {
	constructor(private readonly blockService: BlockService) {}

	@httpPost(':targetUserId')
	@httpCode(HttpStatus.NO_CONTENT)
	@apiOperation({summary: '사용자 차단 — DM 메시지 수신 거부'})
	@apiParameter({name: 'targetUserId', description: '차단할 사용자 ID', type: Number})
	@apiNoContentResponse({description: '차단 성공'})
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
	async unblockUser(
		@currentUserId() userId: number,
		@requestParameter('targetUserId', SafePositiveIntPipe) targetUserId: number,
	) {
		return this.blockService.unblockUser(userId, targetUserId);
	}

	@httpGet()
	@apiOperation({summary: '차단 목록 조회'})
	@apiOkResponse({schema: {type: 'array', items: {type: 'number'}, example: [2, 5, 13]}})
	async getBlockedUsers(@currentUserId() userId: number) {
		return this.blockService.getBlockedUsers(userId);
	}
}
