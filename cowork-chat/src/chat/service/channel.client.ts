import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { getRequiredConfig } from '../../common/config/config.util';
import { BaseHttpClient } from './base-http-client';

/**
 * channel-service로부터 반환되는 채널 기본 정보.
 */
export interface ChannelInfo {
    id: number;
    viewType: string;
}

/**
 * channel-service와 HTTP 통신하는 클라이언트.
 *
 * 환경변수 `CHANNEL_SERVICE_URL`을 베이스 URL로 사용하며,
 * 말미 슬래시는 자동으로 제거된다.
 */
@Injectable()
export class ChannelClient extends BaseHttpClient {
    protected readonly logger = new Logger(ChannelClient.name);
    protected readonly serviceName = 'channel-service';
    private readonly channelServiceUrl: string;

    constructor(private readonly configService: ConfigService) {
        super();
        this.channelServiceUrl = getRequiredConfig(this.configService, 'CHANNEL_SERVICE_URL').replace(/\/$/, '');
    }

    /**
     * 채널 ID로 채널 정보를 조회한다.
     *
     * 요청 시 `X-User-Id` 헤더에 호출자의 사용자 ID를 포함하여 전송한다.
     * channel-service가 해당 헤더로 접근 권한을 검증하므로 반드시 필요하다.
     * 응답 본문의 `id`와 `viewType` 필드 타입을 직접 검증하며,
     * 형식이 맞지 않으면 예외를 던진다.
     *
     * @param channelId - 조회할 채널의 ID
     * @param userId - 요청을 수행하는 사용자의 ID (`X-User-Id` 헤더로 전달됨)
     * @returns 채널 ID와 뷰 타입이 포함된 {@link ChannelInfo}
     * @throws {Error} channel-service가 4xx/5xx 응답을 반환한 경우
     * @throws {Error} 응답 본문의 형식이 올바르지 않은 경우
     */
    async getChannel(channelId: number, userId: number): Promise<ChannelInfo> {
        const res = await fetch(`${this.channelServiceUrl}/channels/${channelId}`, {
            headers: { 'X-User-Id': String(userId) },
            signal: AbortSignal.timeout(3000),
        });

        if (!res.ok) {
            const message = await this.readErrorMessage(res);
            throw new Error(`channel-service 오류: ${res.status}${message ? ` - ${message}` : ''}`);
        }

        const body = await this.readJsonBody<{ id?: number; viewType?: string }>(res);
        if (typeof body.id !== 'number' || typeof body.viewType !== 'string') {
            throw new Error('channel-service 응답 형식이 올바르지 않습니다');
        }

        return {
            id: body.id,
            viewType: body.viewType,
        };
    }
}
