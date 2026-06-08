import { createParamDecorator, ExecutionContext } from '@nestjs/common';
import { RequestContextUtil } from '../util/request-context.util';

export const UserId = createParamDecorator(
    (data: unknown, ctx: ExecutionContext): number => {
        const request = ctx.switchToHttp().getRequest();
        return RequestContextUtil.getUserId(request.headers);
    },
);

export const UserRole = createParamDecorator(
    (data: unknown, ctx: ExecutionContext): string => {
        const request = ctx.switchToHttp().getRequest();
        return RequestContextUtil.getUserRole(request.headers);
    },
);
