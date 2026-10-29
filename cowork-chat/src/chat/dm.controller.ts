import {
	Controller as controller,
	Delete as httpDelete,
	Get as httpGet,
	HttpCode as httpCode,
	HttpStatus,
	Param as requestParameter,
} from '@nestjs/common';
import {
	ApiHeader as apiHeader,
	ApiNoContentResponse as apiNoContentResponse,
	ApiOkResponse as apiOkResponse,
	ApiOperation as apiOperation,
	ApiParam as apiParameter,
	ApiTags as apiTags,
} from '@nestjs/swagger';
import {currentUserId} from '../common/decorator/user.decorator.js';
import {SafePositiveIntPipe} from '../common/pipe/safe-positive-int.pipe.js';
import {ChatService} from './chat.service.js';

@apiTags('DM')
@apiHeader({name: 'X-User-Id', description: 'Gateway 자동 주입 (서비스 직접 테스트 시만 입력)', required: false})
@controller('dms')
export class DmController {
	constructor(private readonly chatService: ChatService) {}

	@httpGet()
	@apiOperation({summary: '내 DM 대화 목록 조회 — 숨긴 대화 제외, 마지막 메시지 시각 내림차순'})
	@apiOkResponse({description: '채널 ID, 상대 사용자 ID, 안읽음 수, 마지막 메시지 요약 목록'})
	async getMyDms(@currentUserId() userId: number) {
		return this.chatService.getMyDms(userId);
	}

	@httpDelete(':channelId')
	@httpCode(HttpStatus.NO_CONTENT)
	@apiOperation({summary: 'DM 대화 숨기기 — 상대방 메시지 수신 시 자동 복구'})
	@apiParameter({name: 'channelId', description: '숨길 DM 채널 ID', type: Number})
	@apiNoContentResponse({description: '숨김 성공'})
	async hideDm(
		@currentUserId() userId: number,
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
	) {
		return this.chatService.hideDm(channelId, userId);
	}
}
