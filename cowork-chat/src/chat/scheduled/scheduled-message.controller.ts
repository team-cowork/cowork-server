import {
	Body as requestBody,
	Controller as controller,
	Delete as httpDelete,
	Get as httpGet,
	HttpCode as httpCode,
	HttpStatus,
	Param as requestParameter,
	Patch as httpPatch,
	Post as httpPost,
} from '@nestjs/common';
import {
	ApiExtraModels as apiExtraModels,
	ApiNoContentResponse as apiNoContentResponse,
	ApiOperation as apiOperation,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
	getSchemaPath,
} from '@nestjs/swagger';
import {ApiResponseMetadataDto, apiEnvelopeResponse} from '../../common/dto/api-response-metadata.dto.js';
import {currentUserId} from '../../common/decorator/user.decorator.js';
import {ObjectIdPipe} from '../../common/pipe/object-id.pipe.js';
import {SafePositiveIntPipe} from '../../common/pipe/safe-positive-int.pipe.js';
import {
	CreateScheduledMessageDto,
	ScheduledMessageResponseDto,
	UpdateScheduledMessageDto,
} from './scheduled-message.dto.js';
import {ScheduledMessageService} from './scheduled-message.service.js';

/**
 예약 메시지 REST 컨트롤러. 채널과 DM의 최상위 메시지만 예약할 수 있다.
 */
@apiTags('Scheduled Message')
@apiExtraModels(ApiResponseMetadataDto, ScheduledMessageResponseDto)
@apiEnvelopeResponse(400, '요청 값이 올바르지 않음')
@apiEnvelopeResponse(401, '액세스 토큰이 없거나 유효하지 않음')
@apiEnvelopeResponse(429, '요청 한도 초과')
@apiResponse({status: 'default', description: '기타 오류', type: ApiResponseMetadataDto})
@controller()
export class ScheduledMessageController {
	constructor(private readonly scheduledMessageService: ScheduledMessageService) {}

	@httpPost('channels/:channelId/scheduled-messages')
	@httpCode(HttpStatus.CREATED)
	@apiOperation({
		summary: '메시지 예약',
		description: '정한 시각에 일반 메시지와 같은 경로로 발송합니다. 발송할 때 권한, 채널 보관 여부, DM 차단을 다시 검사합니다. 같은 clientMessageId로 다시 요청하면 처음 만든 예약을 반환합니다.',
	})
	@apiEnvelopeResponse(201, '요청 처리 성공', ScheduledMessageResponseDto)
	@apiEnvelopeResponse(403, 'message_send 또는 file_attach 권한 없음, DM 수신자가 차단함')
	@apiEnvelopeResponse(404, '채널 없음')
	@apiEnvelopeResponse(409, '보관된 채널, 대기 중인 예약 100건 초과, 다른 요청이 쓴 clientMessageId')
	@apiEnvelopeResponse(503, '권한 정보 동기화 중')
	async create(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestBody() dto: CreateScheduledMessageDto,
		@currentUserId() userId: number,
	): Promise<ScheduledMessageResponseDto> {
		return this.scheduledMessageService.create({channelId, userId}, dto);
	}

	@httpGet('scheduled-messages')
	@apiOperation({summary: '내 예약 메시지 목록 조회', description: '대기 중인 예약을 발송 시각 순서로 먼저 반환하고, 실패한 예약을 최근에 실패한 순서로 최대 100건 반환합니다. 실패한 예약은 30일 동안 보관합니다.'})
	@apiEnvelopeResponse(200, '예약 메시지 목록', {type: 'array', items: {$ref: getSchemaPath(ScheduledMessageResponseDto)}})
	async list(@currentUserId() userId: number): Promise<ScheduledMessageResponseDto[]> {
		return this.scheduledMessageService.list(userId);
	}

	@httpPatch('scheduled-messages/:scheduledMessageId')
	@apiOperation({summary: '예약 메시지 수정', description: '발송 전인 PENDING 예약의 내용과 발송 시각을 바꿉니다.'})
	@apiEnvelopeResponse(200, '요청 처리 성공', ScheduledMessageResponseDto)
	@apiEnvelopeResponse(404, '예약 메시지 없음')
	@apiEnvelopeResponse(409, '발송 중이거나 발송이 끝난 예약')
	async update(
		@requestParameter('scheduledMessageId', ObjectIdPipe) scheduledMessageId: string,
		@requestBody() dto: UpdateScheduledMessageDto,
		@currentUserId() userId: number,
	): Promise<ScheduledMessageResponseDto> {
		return this.scheduledMessageService.update(userId, scheduledMessageId, dto);
	}

	@httpDelete('scheduled-messages/:scheduledMessageId')
	@httpCode(HttpStatus.NO_CONTENT)
	@apiOperation({summary: '예약 메시지 취소', description: 'PENDING 예약을 취소하거나 FAILED 예약을 목록에서 지웁니다.'})
	@apiNoContentResponse({description: '요청 처리 성공'})
	@apiEnvelopeResponse(404, '예약 메시지 없음')
	@apiEnvelopeResponse(409, '발송 중이거나 발송한 예약')
	async cancel(
		@requestParameter('scheduledMessageId', ObjectIdPipe) scheduledMessageId: string,
		@currentUserId() userId: number,
	): Promise<void> {
		await this.scheduledMessageService.cancel(userId, scheduledMessageId);
	}
}
