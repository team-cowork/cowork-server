import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { getRequiredConfig } from '../../common/config/config.util';

@Injectable()
export class UserClient {
    private readonly logger = new Logger(UserClient.name);
    private readonly userServiceUrl: string;

    constructor(private readonly configService: ConfigService) {
        this.userServiceUrl = getRequiredConfig(this.configService, 'USER_SERVICE_URL').replace(/\/$/, '');
    }

    async getDisplayName(userId: number): Promise<string> {
        const res = await fetch(`${this.userServiceUrl}/users/${userId}`, {
            signal: AbortSignal.timeout(3000),
        });

        if (!res.ok) {
            const message = await this.readErrorMessage(res);
            throw new Error(`user-service 오류: ${res.status}${message ? ` - ${message}` : ''}`);
        }

        const body = await res.json() as { name?: string; nickname?: string | null };
        if (typeof body.name !== 'string' || body.name.length === 0) {
            throw new Error('user-service 응답 형식이 올바르지 않습니다');
        }

        if (typeof body.nickname === 'string' && body.nickname.length > 0) {
            return body.nickname;
        }

        return body.name;
    }

    private async readErrorMessage(res: Response): Promise<string | null> {
        try {
            return await res.text();
        } catch (err) {
            this.logger.warn(`user-service 오류 응답 본문 읽기 실패: ${String(err)}`);
            return null;
        }
    }
}
