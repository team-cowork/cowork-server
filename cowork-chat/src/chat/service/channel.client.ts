import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { getRequiredConfig } from '../../common/config/config.util';

export interface ChannelInfo {
    id: number;
    viewType: string;
}

@Injectable()
export class ChannelClient {
    private readonly logger = new Logger(ChannelClient.name);
    private readonly channelServiceUrl: string;

    constructor(private readonly configService: ConfigService) {
        this.channelServiceUrl = getRequiredConfig(this.configService, 'CHANNEL_SERVICE_URL').replace(/\/$/, '');
    }

    async getChannel(channelId: number, userId: number): Promise<ChannelInfo> {
        const res = await fetch(`${this.channelServiceUrl}/channels/${channelId}`, {
            headers: { 'X-User-Id': String(userId) },
            signal: AbortSignal.timeout(3000),
        });

        if (!res.ok) {
            const message = await this.readErrorMessage(res);
            throw new Error(`channel-service 오류: ${res.status}${message ? ` - ${message}` : ''}`);
        }

        const body = await res.json() as { id?: number; viewType?: string };
        if (typeof body.id !== 'number' || typeof body.viewType !== 'string') {
            throw new Error('channel-service 응답 형식이 올바르지 않습니다');
        }

        return {
            id: body.id,
            viewType: body.viewType,
        };
    }

    private async readErrorMessage(res: Response): Promise<string | null> {
        try {
            return await res.text();
        } catch (err) {
            this.logger.warn(`channel-service 오류 응답 본문 읽기 실패: ${String(err)}`);
            return null;
        }
    }
}
