import { Injectable, ServiceUnavailableException } from '@nestjs/common';
import { ChannelProjectionRepository } from '../repository/channel-projection.repository';

export interface ChannelInfo {
    id: number;
    viewType: string;
}

/** Kafka로 동기화된 로컬 채널 projection 조회기. */
@Injectable()
export class ChannelClient {
    constructor(private readonly projectionRepository: ChannelProjectionRepository) {}

    /**
     * 채널 메타데이터는 로컬 projection에서 조회한다.
     * userId는 기존 호출 계약 호환을 위해 유지하며, 접근 권한은 호출부의 채널 멤버십 검사에서 검증한다.
     */
    async getChannel(channelId: number, userId: number): Promise<ChannelInfo> {
        void userId;
        const channel = await this.projectionRepository.findById(channelId);
        if (!channel) {
            throw new ServiceUnavailableException('채널 정보가 아직 동기화되지 않았습니다');
        }
        return { id: channel.channelId, viewType: channel.viewType };
    }
}
