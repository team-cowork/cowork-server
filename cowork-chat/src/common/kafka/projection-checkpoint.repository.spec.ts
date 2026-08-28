import {
    ProjectionAssignmentLease,
    ProjectionCheckpointFenceError,
    ProjectionCheckpointRepository,
} from './projection-checkpoint.repository';

describe('ProjectionCheckpointRepository', () => {
    const lease: ProjectionAssignmentLease = {
        assignmentEpoch: 'epoch-1',
        memberId: 'member-1',
        groupGenerationId: 7,
    };

    it('nextOffset을 BSON Long으로 캐스팅 가능한 bigint $max로 단조 증가시킨다', async () => {
        const updateOne = jest.fn().mockResolvedValue({ matchedCount: 1 });
        const repository = new ProjectionCheckpointRepository({ updateOne } as never, {} as never);

        await repository.advance('group', 'channel.event', 2, lease, '9007199254740994');

        expect(updateOne).toHaveBeenCalledWith(
            expect.objectContaining({
                groupId: 'group',
                topic: 'channel.event',
                partition: 2,
                assignmentEpoch: 'epoch-1',
                assignmentMemberId: 'member-1',
                assignmentGenerationId: 7,
            }),
            { $max: { nextOffset: 9007199254740994n } },
        );
    });

    it('현재 assignment fence와 일치하지 않으면 checkpoint를 갱신하지 않는다', async () => {
        const updateOne = jest.fn().mockResolvedValue({ matchedCount: 0 });
        const repository = new ProjectionCheckpointRepository({ updateOne } as never, {} as never);

        await expect(repository.advance('group', 'channel.event', 0, lease, '11'))
            .rejects.toBeInstanceOf(ProjectionCheckpointFenceError);

        expect(updateOne).toHaveBeenCalledTimes(1);
    });

    it('snapshot marker receipt와 next offset을 한 Mongo update로 저장한다', async () => {
        const updateOne = jest.fn().mockResolvedValue({ matchedCount: 1 });
        const lean = jest.fn().mockResolvedValue({});
        const findOne = jest.fn().mockReturnValue({ lean });
        const repository = new ProjectionCheckpointRepository({ updateOne, findOne } as never, {} as never);
        const occurredAt = new Date('2026-08-26T11:00:00Z');

        await repository.advance('group', 'channel.event', 2, lease, '8', {
            offset: '7',
            snapshotId: '93b19168-4a63-49cd-b01d-b8d0667a1cb5',
            source: 'cowork-channel',
            occurredAt,
        });
        const expectedSnapshotFields: unknown = expect.objectContaining({
            snapshotId: '93b19168-4a63-49cd-b01d-b8d0667a1cb5',
            snapshotSource: 'cowork-channel',
            snapshotOccurredAt: occurredAt,
        });

        expect(updateOne).toHaveBeenCalledWith(
            expect.objectContaining({
                groupId: 'group',
                topic: 'channel.event',
                partition: 2,
                assignmentEpoch: 'epoch-1',
            }),
            expect.objectContaining({
                $max: { nextOffset: 8n, snapshotCompletedOffset: 7n },
                $set: expectedSnapshotFields,
            }),
        );
    });

    it('malformed record 원문을 offset별로 멱등 격리한다', async () => {
        const quarantineUpdateOne = jest.fn().mockResolvedValue({ acknowledged: true });
        const repository = new ProjectionCheckpointRepository(
            {} as never,
            { updateOne: quarantineUpdateOne } as never,
        );

        await repository.quarantine('group', 'channel.event', 2, '9007199254740994', '3', '{bad', 'invalid json');

        const key = {
            groupId: 'group',
            topic: 'channel.event',
            partition: 2,
            messageOffset: 9007199254740994n,
        };
        expect(quarantineUpdateOne).toHaveBeenCalledWith(
            key,
            {
                $setOnInsert: {
                    ...key,
                    eventKey: '3',
                    payload: '{bad',
                    reason: 'invalid json',
                },
            },
            { upsert: true },
        );
    });
});
