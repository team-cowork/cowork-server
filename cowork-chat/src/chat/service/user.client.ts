import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { getRequiredConfig } from '../../common/config/config.util';
import { BaseHttpClient } from './base-http-client';

/**
 * user-service와 HTTP 통신하는 클라이언트.
 *
 * 환경변수 `USER_SERVICE_URL`을 베이스 URL로 사용하며,
 * 말미 슬래시는 자동으로 제거된다.
 */
@Injectable()
export class UserClient extends BaseHttpClient {
    protected readonly logger = new Logger(UserClient.name);
    protected readonly serviceName = 'user-service';
    private readonly userServiceUrl: string;

    constructor(private readonly configService: ConfigService) {
        super();
        this.userServiceUrl = getRequiredConfig(this.configService, 'USER_SERVICE_URL').replace(/\/$/, '');
    }

    /**
     * 여러 사용자의 표시 이름을 단일 요청으로 일괄 조회한다.
     *
     * uploader 수만큼 개별 요청을 반복하는 N+1 패턴을 피하기 위해
     * user-service의 `GET /users/batch` 엔드포인트를 사용한다.
     * 응답에 포함되지 않은 userId(예: 존재하지 않는 사용자, 형식이 올바르지 않은 항목)는
     * 반환되는 Map에서 생략된다 — 일부 항목이 깨져 있어도 나머지 조회는 계속 진행한다.
     *
     * @param userIds - 조회할 사용자 ID 목록
     * @returns userId → 표시 이름 매핑 (조회 실패한 id는 포함되지 않음)
     * @throws {Error} user-service가 4xx/5xx 응답을 반환한 경우
     */
    async getDisplayNames(userIds: number[]): Promise<Map<number, string>> {
        if (userIds.length === 0) return new Map();

        const res = await this.fetchWithRetry(`${this.userServiceUrl}/users/batch?ids=${userIds.join(',')}`, {}, 3000);

        if (!res.ok) {
            const message = await this.readErrorMessage(res);
            throw new Error(`user-service 오류: ${res.status}${message ? ` - ${message}` : ''}`);
        }

        const body = await this.readJsonBody<{
            users?: Array<{ id?: number; name?: string; nickname?: string | null }>;
        }>(res);
        if (!Array.isArray(body.users)) {
            throw new Error('user-service 응답 형식이 올바르지 않습니다');
        }

        const names = new Map<number, string>();
        for (const user of body.users) {
            if (typeof user.id !== 'number' || typeof user.name !== 'string' || user.name.length === 0) continue;
            names.set(user.id, this.resolveDisplayName(user.name, user.nickname));
        }
        return names;
    }

    private resolveDisplayName(name: string, nickname: string | null | undefined): string {
        return typeof nickname === 'string' && nickname.length > 0 ? nickname : name;
    }
}
