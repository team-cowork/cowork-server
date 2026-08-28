import { ServiceUnavailableException } from '@nestjs/common';
import { ChannelProjectionRepository } from '../repository/channel-projection.repository';
import { ChannelClient } from './channel.client';

describe('ChannelClient', () => {
    const projectionRepository = {
        findById: jest.fn(),
    };
    const client = new ChannelClient(projectionRepository as unknown as ChannelProjectionRepository);

    beforeEach(() => jest.clearAllMocks());

    it('Kafka로 동기화된 채널 projection에서 viewType을 조회한다', async () => {
        projectionRepository.findById.mockResolvedValue({
            channelId: 1,
            teamId: 10,
            name: 'files',
            type: 'TEXT',
            viewType: 'FILE_SHARE',
            description: null,
            isPrivate: false,
            position: 0,
        });

        await expect(client.getChannel(1)).resolves.toEqual({ id: 1, viewType: 'FILE_SHARE' });
        expect(projectionRepository.findById).toHaveBeenCalledWith(1);
    });

    it('projection이 아직 동기화되지 않았으면 원격 호출 없이 fail-closed한다', async () => {
        projectionRepository.findById.mockResolvedValue(null);

        await expect(client.getChannel(99)).rejects.toBeInstanceOf(ServiceUnavailableException);
    });
});
