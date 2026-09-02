import { ProjectionDatasetRepository } from './projection-dataset.repository';

describe('ProjectionDatasetRepository', () => {
    const createRepository = () => {
        const models = Array.from({ length: 7 }, () => ({
            countDocuments: jest.fn().mockResolvedValue(0),
            deleteMany: jest.fn().mockResolvedValue({ deletedCount: 0 }),
            updateMany: jest.fn().mockResolvedValue({ modifiedCount: 0 }),
        }));
        const repository = new ProjectionDatasetRepository(
            {} as never,
            models[0] as never,
            models[1] as never,
            models[2] as never,
            models[3] as never,
            models[4] as never,
            models[5] as never,
            models[6] as never,
        );
        return { repository, models };
    };

    it('projection 전용 stream rebuild는 stale document를 모두 제거한다', async () => {
        const { repository, models } = createRepository();

        await repository.resetProjection('channel');

        expect(models[0].deleteMany).toHaveBeenCalledWith({});
        expect(models[0].updateMany).not.toHaveBeenCalled();
    });

    it('channelMember rebuild는 chat 소유 필드를 삭제하지 않고 projection 부분만 tombstone으로 초기화한다', async () => {
        const { repository, models } = createRepository();

        await repository.resetProjection('channelMember');

        expect(models[2].deleteMany).not.toHaveBeenCalled();
        expect(models[2].updateMany).toHaveBeenCalledWith({}, {
            $set: {
                deleted: true,
                sourceOccurredAt: new Date(0),
                sourceVersion: 0n,
            },
        });
        const update = models[2].updateMany.mock.calls[0][1] as { $set: Record<string, unknown> };
        expect(update.$set).not.toHaveProperty('isHidden');
        expect(update.$set).not.toHaveProperty('lastReadMessageId');
    });
});
