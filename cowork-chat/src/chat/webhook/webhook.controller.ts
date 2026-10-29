import {
	Body as requestBody,
	Controller as controller,
	Header as responseHeader,
	Headers as requestHeaders,
	HttpCode as httpCode,
	Param as requestParameter,
	Post as httpPost,
	Res as rawResponse,
} from '@nestjs/common';
import {
	ApiHeader as apiHeader,
	ApiOperation as apiOperation,
	ApiParam as apiParameter,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
} from '@nestjs/swagger';
import {Response} from 'express';
import {publicRoute} from '../../common/guard/public.decorator.js';
import {MessageResponseDto} from '../dto/message-response.dto.js';
import {ExecuteWebhookDto} from './execute-webhook.dto.js';
import {WebhookRateLimitExceeded} from './webhook-rate-limiter.js';
import {WebhookService} from './webhook.service.js';

@apiTags('웹훅 수신')
@controller('webhooks')
export class WebhookController {
	constructor(private readonly service: WebhookService) {}

	@publicRoute()
	@httpPost(':executionId/messages')
	@httpCode(200)
	@responseHeader('Cache-Control', 'no-store')
	@apiOperation({
		summary: '외부 웹훅 메시지 저장',
		description: '관리 API의 executionPath 사용: POST /api/chat/chat/webhooks/{executionId}/{signature}/messages. Gateway가 서명을 검증합니다. content만 저장하며 멘션 확장과 푸시 알림은 지원하지 않습니다.',
		security: [],
	})
	@apiParameter({
		name: 'executionId', type: String, format: 'uuid', description: '관리용 숫자 ID와 별개인 웹훅 실행 UUIDv4',
	})
	@apiHeader({name: 'X-Webhook-Secret', required: false, description: '보안 모드에서 필수인 웹훅 비밀값'})
	@apiHeader({name: 'Idempotency-Key', required: false, description: '같은 웹훅의 동일 메시지 재전송 키, 최대 200자'})
	@apiResponse({status: 200, type: MessageResponseDto, description: '메시지 저장 완료 또는 기존 동일 메시지 반환'})
	@apiResponse({status: 401, description: '웹훅 비밀값 불일치'})
	@apiResponse({status: 403, description: 'Gateway에서 실행 URL 서명 검증 실패'})
	@apiResponse({status: 409, description: 'Idempotency-Key를 다른 본문에 재사용함'})
	@apiResponse({status: 410, description: '같은 Idempotency-Key로 저장한 메시지가 삭제됨'})
	@apiResponse({status: 429, description: '요청 한도 초과, Retry-After 초 뒤 재시도'})
	@apiResponse({status: 503, description: 'Gateway 서명 키, 설정 동기화 또는 요청 제한 확인 불가'})
	async execute(
		@requestParameter('executionId') executionId: string,
		@requestBody() body: ExecuteWebhookDto,
		@requestHeaders() headers: Record<string, string | undefined>,
		@rawResponse({passthrough: true}) response: Response,
	) {
		const secret = headers['x-webhook-secret'];
		const idempotencyKey = headers['idempotency-key'];
		try {
			return await this.service.execute(executionId, {
				content: body.content, secret, idempotencyKey,
			});
		} catch (error) {
			if (error instanceof WebhookRateLimitExceeded) {
				response.setHeader('Retry-After', error.retryAfterSeconds);
			}

			throw error;
		}
	}
}
