import { Model } from 'mongoose';
import { ChannelMember } from '../schema/channel-member.schema';
import { ChannelMemberRepository } from './channel-member.repository';

describe('ChannelMemberRepository', () => {
    const lean = jest.fn();
    const model = { find: jest.fn(() => ({ lean })) };
    const repository = new ChannelMemberRepository(model as unknown as Model<ChannelMember>);

    beforeEach(() => {
        jest.clearAllMocks();
        lean.mockResolvedValue([
            { channelId: 10, userId: 2 },
            { channelId: 20, userId: 3 },
        ]);
    });

    it('batch access 평가 시 요청된 channel/user 멤버십만 조회한다', async () => {
        await expect(repository.findByChannelIdsAndUserIds([10, 20], [2, 3])).resolves.toEqual(new Map([
            [10, [{ channelId: 10, userId: 2 }]],
            [20, [{ channelId: 20, userId: 3 }]],
        ]));

        expect(model.find).toHaveBeenCalledWith({
            channelId: { $in: [10, 20] },
            userId: { $in: [2, 3] },
            deleted: { $ne: true },
        });
    });
});
