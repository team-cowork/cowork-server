import { CanActivate, ExecutionContext, ForbiddenException, Injectable, UnauthorizedException } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { Request } from 'express';
import { UserRole } from '../enum/user-role.enum';
import { IS_PUBLIC_KEY } from './public.decorator';
import { ROLES_KEY } from './roles.decorator';

@Injectable()
export class AuthGuard implements CanActivate {
    constructor(private readonly reflector: Reflector) {}

    canActivate(context: ExecutionContext): boolean {
        const isPublic = this.reflector.getAllAndOverride<boolean>(IS_PUBLIC_KEY, [
            context.getHandler(),
            context.getClass(),
        ]);
        if (isPublic) return true;

        const request = context.switchToHttp().getRequest<Request>();
        const path = request.path;
        if (path === '/metrics') return true;

        const userId = request.headers['x-user-id'];
        if (!userId) throw new UnauthorizedException('x-user-id 헤더가 없습니다');

        const requiredRoles = this.reflector.getAllAndOverride<UserRole[]>(ROLES_KEY, [
            context.getHandler(),
            context.getClass(),
        ]);
        if (!requiredRoles || requiredRoles.length === 0) return true;

        const userRole = request.headers['x-user-role'] as string | undefined;
        if (!requiredRoles.includes(userRole as UserRole)) {
            throw new ForbiddenException('접근 권한이 없습니다');
        }
        return true;
    }
}
