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
	ApiExtraModels as apiExtraModels,
	getSchemaPath,
} from '@nestjs/swagger';
import {Response} from 'express';
import {ApiResponseMetadataDto} from '../../common/dto/api-response-metadata.dto.js';
import {publicRoute} from '../../common/guard/public.decorator.js';
import {MessageResponseDto} from '../dto/message-response.dto.js';
import {ExecuteWebhookDto} from './execute-webhook.dto.js';
import {WebhookRateLimitExceeded} from './webhook-rate-limiter.js';
import {WebhookService} from './webhook.service.js';

@apiTags('웹훅 수신')
@apiExtraModels(ApiResponseMetadataDto, MessageResponseDto)
@apiResponse({status: 'default', description: '기타 오류', type: ApiResponseMetadataDto})
@controller('webhooks')
export class WebhookController {
	constructor(private readonly service: WebhookService) {}

	@publicRoute()
	@httpPost(':executionId/messages')
	@httpCode(200)
	@responseHeader('Cache-Control', 'no-store')
	@apiOperation({
		summary: '외부 웹훅 메시지 저장',
		description: '웹훅 관리 API에서 받은 executionPath를 그대로 호출합니다. content만 저장하며 멘션을 확장하거나 푸시 알림을 보내지 않습니다.',
		security: [],
	})
	@apiParameter({
		name: 'executionId', type: String, format: 'uuid', description: '관리용 숫자 ID와 별개인 웹훅 실행 UUIDv4',
	})
	@apiParameter({name: 'signature', type: String, description: 'executionPath에 포함된 서명'})
	@apiHeader({name: 'X-Webhook-Secret', required: false, description: '보안 모드에서 필수인 웹훅 비밀값'})
	@apiHeader({name: 'Idempotency-Key', required: false, description: '같은 웹훅의 동일 메시지 재전송 키, 최대 200자'})
	@apiResponse({
		status: 200,
		description: '메시지 저장 완료 또는 기존 동일 메시지 반환',
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
						data: {$ref: getSchemaPath(MessageResponseDto)},
					},
				},
			],
		},
	})
	@apiResponse({
		status: 400,
		description: '본문, 실행 ID 또는 Idempotency-Key 형식 오류',
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
		status: 401,
		description: '웹훅 비밀값 불일치',
		schema: {
			allOf: [
				{$ref: getSchemaPath(ApiResponseMetadataDto)},
				{
					type: 'object',
					properties: {status: {type: 'string', enum: ['401 UNAUTHORIZED']}, code: {type: 'integer', enum: [401]}},
				},
			],
		},
	})
	@apiResponse({
		status: 403,
		description: '실행 URL 서명 검증 실패 또는 비활성 웹훅',
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
	@apiResponse({
		status: 404,
		description: '웹훅 또는 채널 없음',
		schema: {
			allOf: [
				{$ref: getSchemaPath(ApiResponseMetadataDto)},
				{
					type: 'object',
					properties: {status: {type: 'string', enum: ['404 NOT_FOUND']}, code: {type: 'integer', enum: [404]}},
				},
			],
		},
	})
	@apiResponse({
		status: 409,
		description: 'Idempotency-Key를 다른 본문에 재사용함',
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
	@apiResponse({
		status: 410,
		description: '같은 Idempotency-Key로 저장한 메시지가 삭제됨',
		schema: {
			allOf: [
				{$ref: getSchemaPath(ApiResponseMetadataDto)},
				{
					type: 'object',
					properties: {status: {type: 'string', enum: ['410 GONE']}, code: {type: 'integer', enum: [410]}},
				},
			],
		},
	})
	@apiResponse({
		status: 429,
		description: '요청 한도 초과. Retry-After가 있으면 지정한 시간(초) 후 재시도',
		schema: {
			allOf: [
				{$ref: getSchemaPath(ApiResponseMetadataDto)},
				{
					type: 'object',
					properties: {status: {type: 'string', enum: ['429 TOO_MANY_REQUESTS']}, code: {type: 'integer', enum: [429]}},
				},
			],
		},
	})
	@apiResponse({
		status: 503,
		description: '서명 키, 설정 동기화 또는 요청 제한 확인 불가',
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
