import {
    Body,
    Controller,
    Header,
    Headers,
    HttpCode,
    Param,
    Post,
    Res,
} from '@nestjs/common';
import {
    ApiHeader,
    ApiOperation,
    ApiResponse,
    ApiTags,
} from '@nestjs/swagger';
import {Response} from 'express';
import {Public} from '../../common/guard/public.decorator';
import {SafePositiveIntPipe} from '../../common/pipe/safe-positive-int.pipe';
import {MessageResponseDto} from '../dto/message-response.dto';
import {ExecuteWebhookDto} from './execute-webhook.dto';
import {WebhookRateLimitExceeded} from './webhook-rate-limiter';
import {WebhookService} from './webhook.service';

@ApiTags('웹훅 수신')
@Controller('webhooks')
export class WebhookController {
    constructor(private readonly service: WebhookService) {}

    @Public()
    @Post(':webhookId/messages')
    @HttpCode(200)
    @Header('Cache-Control', 'no-store')
    @ApiOperation({
        summary: '외부 웹훅 메시지 저장',
        description: 'Gateway 경로: POST /api/chat/chat/webhooks/{webhookId}/messages. content만 지원하며 저장 완료 후 응답합니다. 멘션 확장과 푸시 알림은 지원하지 않습니다.',
        security: [],
    })
    @ApiHeader({name: 'X-Webhook-Secret', required: false, description: '보안 모드에서 필수인 웹훅 비밀값'})
    @ApiHeader({name: 'Idempotency-Key', required: false, description: '같은 웹훅의 동일 메시지 재전송 키, 최대 200자'})
    @ApiResponse({status: 200, type: MessageResponseDto, description: '메시지 저장 완료 또는 기존 동일 메시지 반환'})
    @ApiResponse({status: 401, description: '웹훅 비밀값 불일치'})
    @ApiResponse({status: 409, description: 'Idempotency-Key를 다른 본문에 재사용함'})
    @ApiResponse({status: 410, description: '같은 Idempotency-Key로 저장한 메시지가 삭제됨'})
    @ApiResponse({status: 429, description: '요청 한도 초과, Retry-After 초 뒤 재시도'})
    @ApiResponse({status: 503, description: '설정 동기화 또는 요청 제한 확인 불가'})
    async execute(
        @Param('webhookId', SafePositiveIntPipe) webhookId: number,
        @Body() body: ExecuteWebhookDto,
        @Headers('x-webhook-secret') secret: string | undefined,
        @Headers('x-webhook-client-ip') clientIp: string | undefined,
        @Headers('idempotency-key') idempotencyKey: string | undefined,
        @Res({passthrough: true}) response: Response,
    ) {
        try {
            return await this.service.execute(webhookId, {
                content: body.content, secret, clientIp, idempotencyKey,
            });
        } catch (error) {
            if (error instanceof WebhookRateLimitExceeded) {
                response.setHeader('Retry-After', error.retryAfterSeconds);
            }

            throw error;
        }
    }
}
