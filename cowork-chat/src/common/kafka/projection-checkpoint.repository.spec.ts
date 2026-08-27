import { ProjectionCheckpointRepository } from './projection-checkpoint.repository';

describe('ProjectionCheckpointRepository', () => {
    it('nextOffset을 BSON Long으로 캐스팅 가능한 bigint $max로 단조 증가시킨다', async () => {
        const updateOne = jest.fn().mockResolvedValue({ acknowledged: true });
        const repository = new ProjectionCheckpointRepository({ updateOne } as never, {} as never);

        await repository.advance('group', 'channel.event', 2, '9007199254740994');

        expect(updateOne).toHaveBeenCalledWith(
            { groupId: 'group', topic: 'channel.event', partition: 2 },
            {
                $setOnInsert: { groupId: 'group', topic: 'channel.event', partition: 2 },
                $max: { nextOffset: 9007199254740994n },
            },
            { upsert: true },
        );
    });

    it('rebalance 중 최초 upsert가 충돌하면 기존 checkpoint에 $max를 재시도한다', async () => {
        const updateOne = jest.fn()
            .mockRejectedValueOnce({ code: 11000 })
            .mockResolvedValueOnce({ acknowledged: true });
        const repository = new ProjectionCheckpointRepository({ updateOne } as never, {} as never);

        await expect(repository.advance('group', 'channel.event', 0, '11')).resolves.toBeUndefined();

        expect(updateOne).toHaveBeenNthCalledWith(
            2,
            { groupId: 'group', topic: 'channel.event', partition: 0 },
            { $max: { nextOffset: 11n } },
        );
    });

    it('snapshot marker receipt와 next offset을 한 Mongo update로 저장한다', async () => {
        const updateOne = jest.fn().mockResolvedValue({ acknowledged: true });
        const repository = new ProjectionCheckpointRepository({ updateOne } as never, {} as never);
        const occurredAt = new Date('2026-08-26T11:00:00Z');

        await repository.advance('group', 'channel.event', 2, '8', {
            offset: '7',
            snapshotId: '93b19168-4a63-49cd-b01d-b8d0667a1cb5',
            source: 'cowork-channel',
            occurredAt,
        });

        expect(updateOne).toHaveBeenCalledWith(
            { groupId: 'group', topic: 'channel.event', partition: 2 },
            {
                $setOnInsert: { groupId: 'group', topic: 'channel.event', partition: 2 },
                $max: { nextOffset: 8n, snapshotCompletedOffset: 7n },
                $set: {
                    snapshotId: '93b19168-4a63-49cd-b01d-b8d0667a1cb5',
                    snapshotSource: 'cowork-channel',
                    snapshotOccurredAt: occurredAt,
                },
            },
            { upsert: true },
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
