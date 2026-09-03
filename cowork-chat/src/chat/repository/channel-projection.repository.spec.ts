import { Model } from 'mongoose';
import { ChannelProjection } from '../schema/channel-projection.schema';
import { ChannelProjectionRepository } from './channel-projection.repository';

describe('ChannelProjectionRepository', () => {
    const lean = jest.fn();
    const sort = jest.fn(() => ({ lean }));
    const model = {
        find: jest.fn(() => ({ sort })),
    };
    const repository = new ChannelProjectionRepository(model as unknown as Model<ChannelProjection>);

    beforeEach(() => {
        jest.clearAllMocks();
        lean.mockResolvedValue([]);
    });

    it('공개 채널과 요청자가 가입한 비공개 채널만 검색한다', async () => {
        await repository.searchVisibleByTeamAndName(10, 'secret.*', [3, 5]);

        expect(model.find).toHaveBeenCalledWith({
            teamId: 10,
            deleted: { $ne: true },
            name: { $regex: 'secret\\.\\*', $options: 'i' },
            $or: [
                { isPrivate: false },
                { isPrivate: true, channelId: { $in: [3, 5] } },
            ],
        });
        expect(sort).toHaveBeenCalledWith({ position: 1, channelId: 1 });
    });
});
