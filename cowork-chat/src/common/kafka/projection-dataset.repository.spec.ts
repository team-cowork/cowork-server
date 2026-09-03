import { ProjectionDatasetRepository } from './projection-dataset.repository';
import { PROJECTION_STREAMS } from './projection-readiness.service';

describe('ProjectionDatasetRepository', () => {
    const createRepository = () => {
        const models = Array.from({ length: 11 }, () => ({
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
            models[7] as never,
            models[8] as never,
            models[9] as never,
            models[10] as never,
        );
        return { repository, models };
    };

    it.each(Object.values(PROJECTION_STREAMS))('$name stream의 projection 문서 수를 조회할 수 있다', async (stream) => {
        const { repository } = createRepository();

        await expect(repository.countProjection(stream.name)).resolves.toBe(0);
    });

    it('teamRole 문서 수는 역할, 할당, member tombstone을 모두 포함한다', async () => {
        const { repository, models } = createRepository();
        models[7].countDocuments.mockResolvedValue(2);
        models[8].countDocuments.mockResolvedValue(3);
        models[9].countDocuments.mockResolvedValue(1);

        await expect(repository.countProjection('teamRole')).resolves.toBe(6);
    });

    it('teamRole rebuild는 역할, 할당, member tombstone을 함께 제거한다', async () => {
        const { repository, models } = createRepository();

        await repository.resetProjection('teamRole');

        for (const model of models.slice(7, 10)) {
            expect(model.deleteMany).toHaveBeenCalledWith({});
            expect(model.updateMany).not.toHaveBeenCalled();
        }
        for (const model of [...models.slice(0, 7), models[10]]) {
            expect(model.deleteMany).not.toHaveBeenCalled();
        }
    });

    it('teamRole의 일부 collection 초기화가 실패하면 rebuild도 실패한다', async () => {
        const { repository, models } = createRepository();
        models[8].deleteMany.mockRejectedValue(new Error('assignment reset failed'));

        await expect(repository.resetProjection('teamRole')).rejects.toThrow('assignment reset failed');
    });

    it('channelRolePolicy의 문서 수 조회와 rebuild는 정책 collection만 처리한다', async () => {
        const { repository, models } = createRepository();
        models[10].countDocuments.mockResolvedValue(4);

        await expect(repository.countProjection('channelRolePolicy')).resolves.toBe(4);
        await repository.resetProjection('channelRolePolicy');

        expect(models[10].deleteMany).toHaveBeenCalledWith({});
        for (const model of models.slice(0, 10)) {
            expect(model.countDocuments).not.toHaveBeenCalled();
            expect(model.deleteMany).not.toHaveBeenCalled();
        }
    });

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
