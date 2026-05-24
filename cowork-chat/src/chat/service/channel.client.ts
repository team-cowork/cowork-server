import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { getRequiredConfig } from '../../common/config/config.util';
import { BaseHttpClient } from './base-http-client';

export interface ChannelInfo {
    id: number;
    viewType: string;
}

@Injectable()
export class ChannelClient extends BaseHttpClient {
    protected readonly logger = new Logger(ChannelClient.name);
    protected readonly serviceName = 'channel-service';
    private readonly channelServiceUrl: string;

    constructor(private readonly configService: ConfigService) {
        super();
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
