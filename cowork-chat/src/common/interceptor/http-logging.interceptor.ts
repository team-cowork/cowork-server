import { CallHandler, ExecutionContext, Injectable, Logger, NestInterceptor } from '@nestjs/common';
import { Request, Response } from 'express';
import { Observable, tap } from 'rxjs';

@Injectable()
export class HttpLoggingInterceptor implements NestInterceptor {
    private readonly logger = new Logger('HttpLog');

    intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
        const request = context.switchToHttp().getRequest<Request>();
        const { method, path } = request;
        const userId = request.headers['x-user-id'] ?? '-';
        const start = Date.now();

        return next.handle().pipe(
            tap(() => {
                const response = context.switchToHttp().getResponse<Response>();
                this.logger.log({
                    method,
                    path,
                    userId,
                    statusCode: response.statusCode,
                    duration: Date.now() - start,
                });
            }),
        );
    }
}
