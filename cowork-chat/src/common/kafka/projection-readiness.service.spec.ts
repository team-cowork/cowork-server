import { Consumer, Kafka } from 'kafkajs';
import {
    ProjectionAssignmentLease,
    ProjectionCheckpointOffset,
    ProjectionCheckpointRepository,
    SnapshotBarrierReceipt,
} from './projection-checkpoint.repository';
import {
    hasCompletedSnapshotBarriers,
    hasReachedStartupOffsets,
    isCheckpointWithinRetainedRange,
    PROJECTION_STREAMS,
    ProjectionReadinessService,
    StartupPartitionOffset,
} from './projection-readiness.service';

describe('ProjectionReadinessService', () => {
    let service: ProjectionReadinessService | undefined;

    const createHarness = (
        topicOffsets: StartupPartitionOffset[],
        storedOffsets: ProjectionCheckpointOffset[] = [],
    ) => {
        const listeners = new Map<string, Array<(event: never) => void>>();
        const rows = new Map<string, ProjectionCheckpointOffset>();
        const rowKey = (topic: string, partition: number) => `${topic}:${partition}`;
        for (const offset of storedOffsets) {
            rows.set(rowKey(PROJECTION_STREAMS.channel.topic, offset.partition), offset);
        }
        const leaseFor = (topic: string, partition: number): ProjectionAssignmentLease => ({
            assignmentEpoch: `epoch-${topic}-${partition}`,
            memberId: 'member-1',
            groupGenerationId: 1,
        });
        const admin = {
            connect: jest.fn().mockResolvedValue(undefined),
            disconnect: jest.fn().mockResolvedValue(undefined),
            fetchTopicOffsets: jest.fn().mockResolvedValue(topicOffsets),
        };
        const kafka = { admin: jest.fn(() => admin) } as unknown as Kafka;
        const seek = jest.fn();
        const pause = jest.fn();
        const resume = jest.fn();
        const consumer = {
            events: {
                GROUP_JOIN: 'consumer.group_join',
                HEARTBEAT: 'consumer.heartbeat',
                REBALANCING: 'consumer.rebalancing',
                DISCONNECT: 'consumer.disconnect',
                STOP: 'consumer.stop',
                CRASH: 'consumer.crash',
            },
            on: jest.fn((eventName: string, listener: (event: never) => void) => {
                listeners.set(eventName, [...(listeners.get(eventName) ?? []), listener]);
                return jest.fn();
            }),
            seek,
            pause,
            resume,
        } as unknown as Consumer;
        const find = jest.fn((_groupId: string, topic: string) => Promise.resolve(
            [...rows.entries()]
                .filter(([key]) => key.startsWith(`${topic}:`))
                .map(([, row]) => row),
        ));
        const claimForAssignment = jest.fn((
            _groupId: string,
            topic: string,
            partition: number,
            earliestOffset: string,
        ) => {
            const lease = leaseFor(topic, partition);
            rows.set(rowKey(topic, partition), {
                partition,
                offset: earliestOffset,
                assignmentEpoch: lease.assignmentEpoch,
                assignmentMemberId: lease.memberId,
                assignmentGenerationId: lease.groupGenerationId,
            });
            return Promise.resolve(lease);
        });
        const advance = jest.fn((
            _groupId: string,
            topic: string,
            partition: number,
            lease: ProjectionAssignmentLease,
            nextOffset: string,
            snapshotBarrier?: SnapshotBarrierReceipt,
        ) => {
            rows.set(rowKey(topic, partition), {
                partition,
                offset: nextOffset,
                assignmentEpoch: lease.assignmentEpoch,
                assignmentMemberId: lease.memberId,
                assignmentGenerationId: lease.groupGenerationId,
                ...(snapshotBarrier ? {
                    snapshotCompletedOffset: snapshotBarrier.offset,
                    snapshotId: snapshotBarrier.snapshotId,
                } : {}),
            });
            return Promise.resolve();
        });
        const checkpoints = {
            find,
            advance,
            claimForAssignment,
            renewAssignment: jest.fn().mockResolvedValue(undefined),
            releaseAssignment: jest.fn().mockResolvedValue(true),
        } as unknown as ProjectionCheckpointRepository;

        const emit = (eventName: string, event: unknown) => {
            for (const listener of listeners.get(eventName) ?? []) listener(event as never);
        };

        return {
            kafka,
            consumer,
            admin,
            checkpoints,
            find,
            advance,
            seek,
            leaseFor,
            setRows: (topic: string, offsets: ProjectionCheckpointOffset[]) => {
                for (const key of [...rows.keys()]) {
                    if (key.startsWith(`${topic}:`)) rows.delete(key);
                }
                for (const offset of offsets) rows.set(rowKey(topic, offset.partition), offset);
            },
            activate: async (stream: typeof PROJECTION_STREAMS[keyof typeof PROJECTION_STREAMS], partitions: number[]) => {
                emit('consumer.group_join', {
                    payload: {
                        groupId: stream.groupId,
                        memberId: 'member-1',
                        memberAssignment: { [stream.topic]: partitions },
                    },
                });
                emit('consumer.heartbeat', {
                    payload: {
                        groupId: stream.groupId,
                        memberId: 'member-1',
                        groupGenerationId: 1,
                    },
                });
                await new Promise<void>((resolve) => setImmediate(resolve));
            },
        };
    };

    afterEach(async () => {
        await service?.onModuleDestroy();
        service = undefined;
    });

    it('모든 파티션의 shared checkpoint가 시작 high-watermark에 도달해야 완료된다', () => {
        const targets: StartupPartitionOffset[] = [
            { partition: 0, low: '0', high: '10', offset: '10' },
            { partition: 1, low: '0', high: '20', offset: '20' },
        ];

        expect(hasReachedStartupOffsets(targets, [
            { partition: 0, offset: '10' },
            { partition: 1, offset: '19' },
        ])).toBe(false);
        expect(hasReachedStartupOffsets(targets, [
            { partition: 0, offset: '10' },
            { partition: 1, offset: '20' },
        ])).toBe(true);
    });

    it('빈 신규 topic도 모든 partition의 full snapshot marker 없이는 준비 완료가 아니다', () => {
        const targets: StartupPartitionOffset[] = [
            { partition: 0, low: '0', high: '0', offset: '0' },
            { partition: 1, low: '0', high: '0', offset: '0' },
        ];

        expect(hasCompletedSnapshotBarriers(targets, [])).toBe(false);
        expect(hasCompletedSnapshotBarriers(targets, [
            {
                partition: 0,
                offset: '1',
                snapshotCompletedOffset: '0',
                assignmentEpoch: 'epoch-0',
                assignmentMemberId: 'member-1',
                assignmentGenerationId: 1,
            },
            { partition: 1, offset: '0' },
        ])).toBe(false);
        expect(hasCompletedSnapshotBarriers(targets, [
            {
                partition: 0,
                offset: '1',
                snapshotCompletedOffset: '0',
                assignmentEpoch: 'epoch-0',
                assignmentMemberId: 'member-1',
                assignmentGenerationId: 1,
            },
            {
                partition: 1,
                offset: '1',
                snapshotCompletedOffset: '0',
                assignmentEpoch: 'epoch-1',
                assignmentMemberId: 'member-1',
                assignmentGenerationId: 1,
            },
        ])).toBe(true);
        expect(hasReachedStartupOffsets(targets, [])).toBe(false);
    });

    it('JavaScript 안전 정수 범위를 넘는 Kafka 오프셋도 정확히 비교한다', () => {
        const targets: StartupPartitionOffset[] = [{
            partition: 0,
            low: '9007199254740992',
            high: '9007199254740994',
            offset: '9007199254740994',
        }];

        expect(hasReachedStartupOffsets(targets, [
            { partition: 0, offset: '9007199254740993' },
        ])).toBe(false);
        expect(hasReachedStartupOffsets(targets, [
            { partition: 0, offset: '9007199254740994' },
        ])).toBe(true);
    });

    it('fresh Mongo에는 broker group commit 대신 earliest offset으로 seek한다', async () => {
        const harness = createHarness([{ partition: 0, low: '3', high: '10', offset: '10' }]);
        service = new ProjectionReadinessService(harness.checkpoints);

        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);

        expect(harness.seek).toHaveBeenCalledWith({
            topic: PROJECTION_STREAMS.channel.topic,
            partition: 0,
            offset: '3',
        });
    });

    it('shared checkpoint가 있어도 새 assignment는 earliest offset부터 재생한다', async () => {
        const harness = createHarness(
            [{ partition: 0, low: '3', high: '10', offset: '10' }],
            [{ partition: 0, offset: '7' }],
        );
        service = new ProjectionReadinessService(harness.checkpoints);

        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);

        expect(harness.seek).toHaveBeenCalledWith({
            topic: PROJECTION_STREAMS.channel.topic,
            partition: 0,
            offset: '3',
        });
    });

    it('retention 이전 checkpoint가 있어도 새 assignment는 retained earliest에서 복구한다', async () => {
        const harness = createHarness(
            [{ partition: 0, low: '3', high: '10', offset: '10' }],
            [{ partition: 0, offset: '1' }],
        );
        service = new ProjectionReadinessService(harness.checkpoints);

        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);
        expect(service.isReady()).toBe(false);
        expect(harness.seek).toHaveBeenCalledWith({
            topic: PROJECTION_STREAMS.channel.topic,
            partition: 0,
            offset: '3',
        });
    });

    it('재생성된 topic도 새 assignment epoch에서 earliest부터 복구한다', async () => {
        const harness = createHarness(
            [{ partition: 0, low: '0', high: '2', offset: '2' }],
            [{ partition: 0, offset: '9' }],
        );
        service = new ProjectionReadinessService(harness.checkpoints);

        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);
        expect(service.isReady()).toBe(false);
        expect(harness.seek).toHaveBeenCalledWith({
            topic: PROJECTION_STREAMS.channel.topic,
            partition: 0,
            offset: '0',
        });
    });

    it('checkpoint replay 가능 범위는 earliest와 high-watermark를 모두 포함한다', () => {
        const target = { partition: 0, low: '3', high: '10', offset: '10' };

        expect(isCheckpointWithinRetainedRange('3', target)).toBe(true);
        expect(isCheckpointWithinRetainedRange('10', target)).toBe(true);
        expect(isCheckpointWithinRetainedRange('2', target)).toBe(false);
        expect(isCheckpointWithinRetainedRange('11', target)).toBe(false);
    });

    it('projection 적용이 끝난 뒤 정확한 next offset을 checkpoint로 저장한다', async () => {
        const harness = createHarness([{
            partition: 0,
            low: '9007199254740992',
            high: '9007199254740994',
            offset: '9007199254740994',
        }]);
        const callOrder: string[] = [];
        harness.advance.mockImplementation(() => {
            callOrder.push('checkpoint');
            return Promise.resolve();
        });
        service = new ProjectionReadinessService(harness.checkpoints);
        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);

        const snapshotBarrier: SnapshotBarrierReceipt = {
            offset: '9007199254740993',
            snapshotId: '93b19168-4a63-49cd-b01d-b8d0667a1cb5',
            source: 'cowork-channel',
            occurredAt: new Date('2026-08-26T11:00:00Z'),
        };

        await service.processMessage(PROJECTION_STREAMS.channel, 0, '9007199254740993', () => {
            callOrder.push('projection');
            return Promise.resolve({ snapshotBarrier });
        });

        expect(callOrder).toEqual(['projection', 'checkpoint']);
        expect(harness.advance).toHaveBeenCalledWith(
            PROJECTION_STREAMS.channel.groupId,
            PROJECTION_STREAMS.channel.topic,
            0,
            harness.leaseFor(PROJECTION_STREAMS.channel.topic, 0),
            '9007199254740994',
            snapshotBarrier,
        );
        expect(service.getStatus().channel).toBe(false);
    });

    it('격리 가능한 invalid record도 handler 반환 후 checkpoint를 전진시킨다', async () => {
        const harness = createHarness([{ partition: 0, low: '0', high: '1', offset: '1' }]);
        service = new ProjectionReadinessService(harness.checkpoints);
        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);

        await service.processMessage(PROJECTION_STREAMS.channel, 0, '0', () => Promise.resolve());

        expect(harness.advance).toHaveBeenCalledWith(
            PROJECTION_STREAMS.channel.groupId,
            PROJECTION_STREAMS.channel.topic,
            0,
            harness.leaseFor(PROJECTION_STREAMS.channel.topic, 0),
            '1',
        );
    });

    it('projection 저장 실패 시 checkpoint를 전진시키지 않는다', async () => {
        const harness = createHarness([{ partition: 0, low: '0', high: '1', offset: '1' }]);
        service = new ProjectionReadinessService(harness.checkpoints);
        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);

        await expect(service.processMessage(
            PROJECTION_STREAMS.channel,
            0,
            '0',
            () => Promise.reject(new Error('mongo failed')),
        )).rejects.toThrow('mongo failed');
        expect(harness.advance).not.toHaveBeenCalled();
    });

    it('runtime high-watermark보다 shared checkpoint가 뒤처지면 readiness를 열지 않는다', async () => {
        const harness = createHarness([{ partition: 0, low: '0', high: '10', offset: '10' }]);
        service = new ProjectionReadinessService(harness.checkpoints);
        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);
        const lease = harness.leaseFor(PROJECTION_STREAMS.channel.topic, 0);
        harness.setRows(PROJECTION_STREAMS.channel.topic, [{
            partition: 0,
            offset: '10',
            snapshotCompletedOffset: '9',
            assignmentEpoch: lease.assignmentEpoch,
            assignmentMemberId: lease.memberId,
            assignmentGenerationId: lease.groupGenerationId,
        }]);
        harness.admin.fetchTopicOffsets.mockResolvedValue([
            { partition: 0, low: '0', high: '100', offset: '100' },
        ]);

        await service.checkCatchup();

        expect(service.getStatus().channel).toBe(false);
        expect(harness.admin.fetchTopicOffsets).toHaveBeenCalledTimes(3);
    });

    it('ready 후 runtime topic 재생성을 감지하면 readiness를 닫고 재시작을 요청한다', async () => {
        const harness = createHarness(
            [{ partition: 0, low: '0', high: '10', offset: '10' }],
            [{ partition: 0, offset: '10', snapshotCompletedOffset: '9' }],
        );
        service = new ProjectionReadinessService(harness.checkpoints);
        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);
        const lease = harness.leaseFor(PROJECTION_STREAMS.channel.topic, 0);
        harness.setRows(PROJECTION_STREAMS.channel.topic, [{
            partition: 0,
            offset: '10',
            snapshotCompletedOffset: '9',
            assignmentEpoch: lease.assignmentEpoch,
            assignmentMemberId: lease.memberId,
            assignmentGenerationId: lease.groupGenerationId,
        }]);
        await service.checkCatchup();
        expect(service.getStatus().channel).toBe(true);

        const violations: string[] = [];
        service.onFatalInvariantViolation((reason) => violations.push(reason));
        harness.admin.fetchTopicOffsets.mockResolvedValue([
            { partition: 0, low: '0', high: '2', offset: '2' },
        ]);

        await service.checkCatchup();

        expect(service.getStatus().channel).toBe(false);
        expect(violations).toHaveLength(1);
        expect(violations[0]).toContain('outside the retained log');
    });

    it('ready 후 snapshot marker receipt가 소실되면 readiness를 다시 닫는다', async () => {
        const harness = createHarness(
            [{ partition: 0, low: '0', high: '10', offset: '10' }],
            [{ partition: 0, offset: '10', snapshotCompletedOffset: '9' }],
        );
        service = new ProjectionReadinessService(harness.checkpoints);
        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);
        const lease = harness.leaseFor(PROJECTION_STREAMS.channel.topic, 0);
        harness.setRows(PROJECTION_STREAMS.channel.topic, [{
            partition: 0,
            offset: '10',
            snapshotCompletedOffset: '9',
            assignmentEpoch: lease.assignmentEpoch,
            assignmentMemberId: lease.memberId,
            assignmentGenerationId: lease.groupGenerationId,
        }]);
        await service.checkCatchup();
        expect(service.getStatus().channel).toBe(true);
        harness.setRows(PROJECTION_STREAMS.channel.topic, [{
            partition: 0,
            offset: '10',
            assignmentEpoch: lease.assignmentEpoch,
            assignmentMemberId: lease.memberId,
            assignmentGenerationId: lease.groupGenerationId,
        }]);

        await service.checkCatchup();

        expect(service.getStatus().channel).toBe(false);
    });

    it('ready 후 일부 partition checkpoint가 소실되면 readiness를 다시 닫는다', async () => {
        const targets: StartupPartitionOffset[] = [
            { partition: 0, low: '0', high: '10', offset: '10' },
            { partition: 1, low: '0', high: '20', offset: '20' },
        ];
        const harness = createHarness(targets, [
            { partition: 0, offset: '10', snapshotCompletedOffset: '9' },
            { partition: 1, offset: '20', snapshotCompletedOffset: '19' },
        ]);
        service = new ProjectionReadinessService(harness.checkpoints);
        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0, 1]);
        const lease0 = harness.leaseFor(PROJECTION_STREAMS.channel.topic, 0);
        const lease1 = harness.leaseFor(PROJECTION_STREAMS.channel.topic, 1);
        harness.setRows(PROJECTION_STREAMS.channel.topic, [
            {
                partition: 0,
                offset: '10',
                snapshotCompletedOffset: '9',
                assignmentEpoch: lease0.assignmentEpoch,
                assignmentMemberId: lease0.memberId,
                assignmentGenerationId: lease0.groupGenerationId,
            },
            {
                partition: 1,
                offset: '20',
                snapshotCompletedOffset: '19',
                assignmentEpoch: lease1.assignmentEpoch,
                assignmentMemberId: lease1.memberId,
                assignmentGenerationId: lease1.groupGenerationId,
            },
        ]);
        await service.checkCatchup();
        expect(service.getStatus().channel).toBe(true);
        harness.setRows(PROJECTION_STREAMS.channel.topic, [{
            partition: 0,
            offset: '10',
            snapshotCompletedOffset: '9',
            assignmentEpoch: lease0.assignmentEpoch,
            assignmentMemberId: lease0.memberId,
            assignmentGenerationId: lease0.groupGenerationId,
        }]);

        await service.checkCatchup();

        expect(service.getStatus().channel).toBe(false);
    });

    it('필수 projection이 모두 등록되고 snapshot marker까지 처리되어야 readiness가 열린다', async () => {
        const harness = createHarness([{ partition: 0, low: '0', high: '1', offset: '1' }]);
        service = new ProjectionReadinessService(harness.checkpoints);
        let readyResolved = false;
        void service.whenReady().then(() => {
            readyResolved = true;
        });

        for (const stream of Object.values(PROJECTION_STREAMS)) {
            await service.registerProjection(harness.kafka, harness.consumer, stream);
            await harness.activate(stream, [0]);
        }
        await Promise.resolve();

        expect(service.isReady()).toBe(false);
        for (const stream of Object.values(PROJECTION_STREAMS)) {
            await service.processMessage(stream, 0, '0', () => Promise.resolve({
                snapshotBarrier: {
                    offset: '0',
                    snapshotId: '93b19168-4a63-49cd-b01d-b8d0667a1cb5',
                    source: `source-for-${stream.topic}`,
                    occurredAt: new Date('2026-08-26T11:00:00Z'),
                },
            }));
        }

        await service.checkCatchup();
        expect(service.isReady()).toBe(true);
        expect(readyResolved).toBe(true);
    });
});
