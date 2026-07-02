import {
    ArgumentsHost,
    Catch,
    ExceptionFilter,
    HttpException,
    HttpStatus,
    Logger,
} from '@nestjs/common';
import { BaseWsExceptionFilter } from '@nestjs/websockets';
import { Response } from 'express';

/**
 * REST 요청에서 발생한 모든 예외를 `{ statusCode, message }` 형태로 통일해 응답한다.
 * `HttpException`이 아닌 예상 못한 예외(NPE, 외부 라이브러리 오류 등)는 스택트레이스를
 * 로그로만 남기고, 클라이언트에는 내부 정보를 노출하지 않는 500 응답을 내려준다.
 *
 * WebSocket 컨텍스트는 Nest 기본 동작과 동일하게 `BaseWsExceptionFilter`에 위임한다.
 * GraphQL 등 그 외 컨텍스트는 필요한 로깅만 하고 그대로 rethrow해
 * Apollo 자체 에러 포맷팅 파이프라인이 원래대로 처리하도록 둔다.
 */
@Catch()
export class GlobalExceptionFilter implements ExceptionFilter {
    private readonly logger = new Logger(GlobalExceptionFilter.name);
    private readonly wsFilter = new BaseWsExceptionFilter();

    catch(exception: unknown, host: ArgumentsHost): void {
        if (host.getType() === 'ws') {
            this.wsFilter.catch(exception, host);
            return;
        }

        if (host.getType() !== 'http') {
            this.logIfUnexpected(exception);
            throw exception;
        }

        const isHttpException = exception instanceof HttpException;
        if (!isHttpException) {
            this.logIfUnexpected(exception);
        }

        const status = isHttpException ? exception.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR;
        const body = isHttpException ? exception.getResponse() : { message: '서버 오류가 발생했습니다' };
        const payload = typeof body === 'string' ? { statusCode: status, message: body } : { statusCode: status, ...body };

        host.switchToHttp().getResponse<Response>().status(status).json(payload);
    }

    private logIfUnexpected(exception: unknown): void {
        if (exception instanceof HttpException) return;
        this.logger.error(
            exception instanceof Error ? exception.message : String(exception),
            exception instanceof Error ? exception.stack : undefined,
        );
    }
}
