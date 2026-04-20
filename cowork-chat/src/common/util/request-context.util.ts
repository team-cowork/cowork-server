import { UnauthorizedException } from '@nestjs/common';

export class RequestContextUtil {
    static getUserId(headers: Record<string, string | string[] | undefined>): number {
        const raw = headers['x-user-id'];
        const value = Array.isArray(raw) ? raw[0] : raw;
        if (!value) throw new UnauthorizedException('x-user-id header missing');
        const id = Number(value);
        if (isNaN(id)) throw new UnauthorizedException('x-user-id is invalid');
        return id;
    }

    static getUserRole(headers: Record<string, string | string[] | undefined>): string {
        const raw = headers['x-user-role'];
        const value = Array.isArray(raw) ? raw[0] : raw;
        return value ?? 'ROLE_USER';
    }
}
