import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { getRequiredConfig } from '../../common/config/config.util';
import { BaseHttpClient } from './base-http-client';

export interface ChannelSearchItem {
    id: number;
    name: string;
    type: string;
    viewType: string;
    description: string | null;
    isPrivate: boolean;
}

@Injectable()
export class ChannelSearchClient extends BaseHttpClient {
    protected readonly logger = new Logger(ChannelSearchClient.name);
    protected readonly serviceName = 'channel-service';
    private readonly channelServiceUrl: string;

    constructor(private readonly configService: ConfigService) {
        super();
        this.channelServiceUrl = getRequiredConfig(this.configService, 'CHANNEL_SERVICE_URL').replace(/\/$/, '');
    }

    async searchChannels(teamId: number, q: string, userId: number): Promise<ChannelSearchItem[]> {
        const url = `${this.channelServiceUrl}/search/channels?teamId=${teamId}&q=${encodeURIComponent(q)}`;
        const res = await fetch(url, {
            headers: { 'X-User-Id': String(userId) },
            signal: AbortSignal.timeout(3000),
        });

        if (!res.ok) {
            const message = await this.readErrorMessage(res);
            throw new Error(`channel-service 오류: ${res.status}${message ? ` - ${message}` : ''}`);
        }

        return this.readJsonBody<ChannelSearchItem[]>(res);
    }
}
