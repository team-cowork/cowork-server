import { mongo } from 'mongoose';
import { ChannelProjectionRepository } from './channel-projection.repository';
import { ProjectMemberProjectionRepository } from './project-member-projection.repository';
import { ProjectProjectionRepository } from './project-projection.repository';
import { TeamMemberProjectionRepository } from './team-member-projection.repository';
import { UserProfileProjectionRepository } from './user-profile-projection.repository';

const EPOCH = new Date(0);
const OCCURRED_AT = new Date('2026-08-26T00:00:00Z');
const SOURCE_VERSION = mongo.Long.fromString('1787702400000000000');
const existingVersion = {
    $ifNull: [
        '$sourceVersion',
        {
            $multiply: [
                { $convert: { input: { $ifNull: ['$sourceOccurredAt', EPOCH] }, to: 'long' } },
                mongo.Long.fromNumber(1_000_000),
            ],
        },
    ],
};
const expectedUpsertCondition = {
    $or: [
        { $lt: [existingVersion, SOURCE_VERSION] },
        {
            $and: [
                { $eq: [existingVersion, SOURCE_VERSION] },
                { $ne: [{ $ifNull: ['$deleted', false] }, true] },
            ],
        },
    ],
};

interface PipelineSet {
    $set: Record<string, unknown>;
}

const modelMock = () => ({
    updateOne: jest.fn<
        Promise<{ modifiedCount: number; upsertedCount: number }>,
        [unknown, PipelineSet[], unknown?]
    >().mockResolvedValue({ modifiedCount: 1, upsertedCount: 0 }),
    updateMany: jest.fn().mockResolvedValue({ modifiedCount: 1 }),
    deleteOne: jest.fn(),
    deleteMany: jest.fn(),
    exists: jest.fn(),
    find: jest.fn(),
    findOne: jest.fn(),
});

const pipelineSet = (model: ReturnType<typeof modelMock>): PipelineSet =>
    model.updateOne.mock.calls[0][1][0];

describe('versioned projection repositories', () => {
    it('채널 UPSERT는 같은 시각의 tombstone보다 우선하지 않는다', async () => {
        const model = modelMock();
        const repository = new ChannelProjectionRepository(model as never);

        await repository.upsert({
            eventType: 'UPDATED',
            channelId: 3,
            teamId: 10,
            projectId: null,
            name: 'release',
            type: 'TEXT',
            viewType: 'CHAT',
            description: null,
            isPrivate: false,
            position: 1,
            occurredAt: OCCURRED_AT,
            sourceVersion: SOURCE_VERSION,
        });

        const deletedField = pipelineSet(model).$set.deleted as { $cond: unknown[] };
        expect(deletedField.$cond[0]).toEqual(expectedUpsertCondition);
        expect(model.updateOne.mock.calls[0][2]).toEqual({
            upsert: true,
            timestamps: false,
            updatePipeline: true,
        });
    });

    it('채널 DELETE는 문서를 지우지 않고 같은 시각까지 우선하는 tombstone을 남긴다', async () => {
        const model = modelMock();
        const repository = new ChannelProjectionRepository(model as never);

        await repository.remove(3, OCCURRED_AT, SOURCE_VERSION);

        expect(model.deleteOne).not.toHaveBeenCalled();
        const deletedField = pipelineSet(model).$set.deleted as { $cond: unknown[] };
        expect(deletedField.$cond[0]).toEqual({ $lte: [existingVersion, SOURCE_VERSION] });
    });

    it('프로젝트 멤버 삭제는 tombstone으로 보존하고 조회에서는 제외한다', async () => {
        const model = modelMock();
        model.exists.mockResolvedValue(null);
        const repository = new ProjectMemberProjectionRepository(model as never);

        await repository.remove(5, 42, OCCURRED_AT, SOURCE_VERSION);
        await repository.exists(5, 42);

        expect(model.deleteOne).not.toHaveBeenCalled();
        expect(model.exists).toHaveBeenCalledWith({ projectId: 5, userId: 42, deleted: { $ne: true } });
    });

    it('프로젝트 lifecycle UPSERT도 같은 시각의 DELETE tombstone을 되살리지 않는다', async () => {
        const model = modelMock();
        const repository = new ProjectProjectionRepository(model as never);

        await repository.upsert({
            projectId: 5,
            teamId: 10,
            name: 'release',
            description: null,
            status: 'ACTIVE',
            position: 1,
            occurredAt: OCCURRED_AT,
            sourceVersion: SOURCE_VERSION,
        });

        const deletedField = pipelineSet(model).$set.deleted as { $cond: unknown[] };
        expect(deletedField.$cond[0]).toEqual(expectedUpsertCondition);
    });

    it('팀 멤버 UPSERT도 같은 시각의 DELETE tombstone을 되살리지 않는다', async () => {
        const model = modelMock();
        const repository = new TeamMemberProjectionRepository(model as never);

        await repository.upsert({
            teamId: 10,
            userId: 42,
            role: 'MEMBER',
            teamName: 'backend',
            occurredAt: OCCURRED_AT,
            sourceVersion: SOURCE_VERSION,
        });

        const deletedField = pipelineSet(model).$set.deleted as { $cond: unknown[] };
        expect(deletedField.$cond[0]).toEqual(expectedUpsertCondition);
    });

    it('사용자 UPSERT도 같은 시각의 DELETE tombstone을 되살리지 않는다', async () => {
        const model = modelMock();
        const repository = new UserProfileProjectionRepository(model as never);

        await repository.upsert({
            userId: 42,
            name: '홍길동',
            nickname: null,
            githubId: null,
            occurredAt: OCCURRED_AT,
            sourceVersion: SOURCE_VERSION,
        });

        const deletedField = pipelineSet(model).$set.deleted as { $cond: unknown[] };
        expect(deletedField.$cond[0]).toEqual(expectedUpsertCondition);
    });
});
