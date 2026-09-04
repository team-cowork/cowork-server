import { Consumer, Kafka } from 'kafkajs';
import {
    ProjectionAssignmentLease,
    ProjectionCheckpointOffset,
    ProjectionCheckpointRepository,
    SnapshotBarrierReceipt,
} from './projection-checkpoint.repository';
import {
    ADMIN_REQUEST_TIMEOUT_MS,
    hasCompletedSnapshotBarriers,
    hasReachedStartupOffsets,
    isCheckpointWithinRetainedRange,
    PROJECTION_STREAMS,
    ProjectionReadinessService,
    StartupPartitionOffset,
} from './projection-readiness.service';
import { ProjectionDatasetRepository, ProjectionDatasetState } from './projection-dataset.repository';
import { ProjectionMetricsService } from './projection-metrics.service';

describe('ProjectionReadinessService', () => {
    let service: ProjectionReadinessService | undefined;

    const createHarness = (
        topicOffsets: StartupPartitionOffset[],
        storedOffsets: ProjectionCheckpointOffset[] = [],
    ) => {
        const listeners = new Map<string, Array<(event: never) => void>>();
        const rows = new Map<string, ProjectionCheckpointOffset>();
        const rowKey = (topic: string, partition: number) => `${topic}:${partition}`;
        const datasetGeneration = 'dataset-generation-1';
        const datasetByStream = new Map<string, ProjectionDatasetState>();
        if (storedOffsets.length > 0) datasetByStream.set('channel', {
            stream: 'channel',
            groupId: PROJECTION_STREAMS.channel.groupId,
            topic: PROJECTION_STREAMS.channel.topic,
            sourceGeneration: PROJECTION_STREAMS.channel.sourceGeneration,
            datasetGeneration,
            mode: 'INCREMENTAL',
            status: 'ACTIVE',
            activationDocumentCount: 0,
            baseOffsets: topicOffsets.map(({ partition, low }) => ({ partition, offset: low })),
            targetOffsets: topicOffsets.map(({ partition, high }) => ({ partition, offset: high })),
        });
        for (const offset of storedOffsets) {
            rows.set(rowKey(PROJECTION_STREAMS.channel.topic, offset.partition), {
                ...offset,
                datasetGeneration,
                sourceGeneration: PROJECTION_STREAMS.channel.sourceGeneration,
                ...(offset.snapshotCompletedOffset === undefined && BigInt(offset.offset) > 0n
                    ? { snapshotCompletedOffset: (BigInt(offset.offset) - 1n).toString() }
                    : {}),
            });
        }
        const leaseFor = (topic: string, partition: number): ProjectionAssignmentLease => ({
            assignmentEpoch: `epoch-${topic}-${partition}`,
            memberId: 'member-1',
            groupGenerationId: 1,
        });
        // fetchTopicOffsets는 인스턴스끼리 공유해 topic 기준으로 한 번에 스텁할 수 있게 한다.
        const fetchTopicOffsets = jest.fn().mockResolvedValue(topicOffsets);
        const adminInstances: Array<{ connect: jest.Mock; disconnect: jest.Mock }> = [];
        const kafkaAdmin = jest.fn(() => {
            const instance = {
                connect: jest.fn().mockResolvedValue(undefined),
                disconnect: jest.fn().mockResolvedValue(undefined),
                fetchTopicOffsets,
            };
            adminInstances.push(instance);
            return instance;
        });
        const admin = { fetchTopicOffsets };
        const kafka = { admin: kafkaAdmin } as unknown as Kafka;
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
        const findAll = jest.fn((_groupId: string, topic: string) => find(_groupId, topic));
        const claimForAssignment = jest.fn((
            _groupId: string,
            topic: string,
            partition: number,
            earliestOffset: string,
            claimedDatasetGeneration: string,
            claimedSourceGeneration: string,
        ) => {
            const lease = leaseFor(topic, partition);
            const existing = rows.get(rowKey(topic, partition));
            const checkpoint: ProjectionCheckpointOffset = {
                ...existing,
                partition,
                offset: existing?.offset ?? earliestOffset,
                datasetGeneration: claimedDatasetGeneration,
                sourceGeneration: claimedSourceGeneration,
                assignmentEpoch: lease.assignmentEpoch,
                assignmentMemberId: lease.memberId,
                assignmentGenerationId: lease.groupGenerationId,
            };
            rows.set(rowKey(topic, partition), checkpoint);
            return Promise.resolve({ lease, checkpoint });
        });
        const advance = jest.fn((
            _groupId: string,
            topic: string,
            partition: number,
            lease: ProjectionAssignmentLease,
            nextOffset: string,
            snapshotBarrier?: SnapshotBarrierReceipt,
        ) => {
            const existing = rows.get(rowKey(topic, partition));
            rows.set(rowKey(topic, partition), {
                ...existing,
                partition,
                offset: nextOffset,
                assignmentEpoch: lease.assignmentEpoch,
                assignmentMemberId: lease.memberId,
                assignmentGenerationId: lease.groupGenerationId,
                ...(snapshotBarrier ? {
                    snapshotCompletedOffset: snapshotBarrier.offset,
                    snapshotId: snapshotBarrier.snapshotId,
                    snapshotOccurredAt: snapshotBarrier.occurredAt,
                } : {}),
            });
            return Promise.resolve();
        });
        const checkpoints = {
            find,
            findAll,
            advance,
            claimForAssignment,
            renewAssignment: jest.fn().mockResolvedValue(undefined),
            releaseAssignment: jest.fn().mockResolvedValue(true),
            acknowledgeRebuildPause: jest.fn().mockResolvedValue(undefined),
            hasUnacknowledgedActiveAssignments: jest.fn().mockResolvedValue(false),
            resetForRebuild: jest.fn((groupId, topic, offsets, nextDatasetGeneration, nextSourceGeneration) => {
                for (const { partition, offset } of offsets) {
                    const existing = rows.get(rowKey(topic, partition));
                    rows.set(rowKey(topic, partition), {
                        ...existing,
                        partition,
                        offset,
                        datasetGeneration: nextDatasetGeneration,
                        sourceGeneration: nextSourceGeneration,
                        snapshotCompletedOffset: undefined,
                        snapshotId: undefined,
                        invalidRecordOffset: undefined,
                    });
                }
                return Promise.resolve();
            }),
        } as unknown as ProjectionCheckpointRepository;
        const resetProjection = jest.fn().mockResolvedValue(undefined);
        const datasets = {
            find: jest.fn((streamName: string) => Promise.resolve(datasetByStream.get(streamName))),
            countProjection: jest.fn().mockResolvedValue(0),
            createBootstrap: jest.fn((stream, baseOffsets, targetOffsets) => {
                const dataset: ProjectionDatasetState = {
                    stream: stream.name,
                    groupId: stream.groupId,
                    topic: stream.topic,
                    sourceGeneration: stream.sourceGeneration,
                    datasetGeneration,
                    mode: 'BOOTSTRAP',
                    status: 'INITIALIZING',
                    activationDocumentCount: 0,
                    baseOffsets,
                    targetOffsets,
                };
                datasetByStream.set(stream.name, dataset);
                return Promise.resolve(dataset);
            }),
            markRebuildRequired: jest.fn((stream, reason) => {
                const dataset: ProjectionDatasetState = {
                    ...(datasetByStream.get(stream.name) ?? {
                        stream: stream.name,
                        groupId: stream.groupId,
                        topic: stream.topic,
                        sourceGeneration: stream.sourceGeneration,
                        datasetGeneration,
                        mode: 'REBUILD',
                        activationDocumentCount: 0,
                        baseOffsets: [],
                        targetOffsets: [],
                    }),
                    status: 'REBUILD_REQUIRED',
                    reason,
                };
                datasetByStream.set(stream.name, dataset);
                return Promise.resolve(dataset);
            }),
            markActive: jest.fn((streamName: string) => {
                const dataset = datasetByStream.get(streamName);
                if (dataset) datasetByStream.set(streamName, { ...dataset, status: 'ACTIVE', mode: 'INCREMENTAL' });
                return Promise.resolve();
            }),
            requestRebuild: jest.fn((stream, reason, baseOffsets, targetOffsets) => {
                const next: ProjectionDatasetState = {
                    stream: stream.name,
                    groupId: stream.groupId,
                    topic: stream.topic,
                    sourceGeneration: stream.sourceGeneration,
                    datasetGeneration: 'dataset-generation-2',
                    mode: 'REBUILD',
                    status: 'REBUILD_REQUESTED',
                    reason,
                    rebuildRequestedAt: new Date('2026-08-26T11:00:00Z'),
                    activationDocumentCount: 0,
                    baseOffsets,
                    targetOffsets,
                };
                datasetByStream.set(stream.name, next);
                return Promise.resolve(next);
            }),
            tryBeginReset: jest.fn().mockResolvedValue(true),
            resetProjection,
            setDataset: (streamName: string, value: ProjectionDatasetState) => {
                datasetByStream.set(streamName, value);
            },
            markRebuilding: jest.fn((streamName: string) => {
                const current = datasetByStream.get(streamName);
                if (current) datasetByStream.set(streamName, { ...current, status: 'REBUILDING' });
                return Promise.resolve();
            }),
        } as unknown as ProjectionDatasetRepository;
        const metrics = {
            recordReplay: jest.fn(),
            recordCatchup: jest.fn(),
            recordRebuild: jest.fn(),
        } as unknown as ProjectionMetricsService;

        const emit = (eventName: string, event: unknown) => {
            for (const listener of listeners.get(eventName) ?? []) listener(event as never);
        };

        return {
            kafka,
            kafkaAdmin,
            adminInstances,
            consumer,
            admin,
            checkpoints,
            datasets,
            metrics,
            resetProjection,
            setDataset: (streamName: string, value: ProjectionDatasetState) => {
                datasetByStream.set(streamName, value);
            },
            find,
            advance,
            seek,
            leaseFor,
            setRows: (topic: string, offsets: ProjectionCheckpointOffset[]) => {
                for (const key of [...rows.keys()]) {
                    if (key.startsWith(`${topic}:`)) rows.delete(key);
                }
                for (const offset of offsets) rows.set(rowKey(topic, offset.partition), {
                    ...offset,
                    datasetGeneration: offset.datasetGeneration ?? datasetGeneration,
                    sourceGeneration: offset.sourceGeneration ?? PROJECTION_STREAMS.channel.sourceGeneration,
                });
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
        jest.useRealTimers();
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
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);

        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);

        expect(harness.seek).toHaveBeenCalledWith({
            topic: PROJECTION_STREAMS.channel.topic,
            partition: 0,
            offset: '3',
        });
    });

    it('유효한 shared checkpoint가 있으면 저장된 next offset부터 증분 재개한다', async () => {
        const harness = createHarness(
            [{ partition: 0, low: '3', high: '10', offset: '10' }],
            [{ partition: 0, offset: '7' }],
        );
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);

        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);

        expect(harness.seek).toHaveBeenCalledWith({
            topic: PROJECTION_STREAMS.channel.topic,
            partition: 0,
            offset: '7',
        });
    });

    it('retention 이전 checkpoint는 자동 low seek 없이 명시적 rebuild를 요구한다', async () => {
        const harness = createHarness(
            [{ partition: 0, low: '3', high: '10', offset: '10' }],
            [{ partition: 0, offset: '1' }],
        );
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);

        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);
        expect(service.isReady()).toBe(false);
        expect(harness.seek).not.toHaveBeenCalled();
        expect(service.getDetailedStatus().channel?.status).toBe('REBUILD_REQUIRED');
    });

    it('checkpoint가 새 topic high보다 크면 자동 replay 없이 명시적 rebuild를 요구한다', async () => {
        const harness = createHarness(
            [{ partition: 0, low: '0', high: '2', offset: '2' }],
            [{ partition: 0, offset: '9' }],
        );
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);

        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);
        expect(service.isReady()).toBe(false);
        expect(harness.seek).not.toHaveBeenCalled();
        expect(service.getDetailedStatus().channel?.status).toBe('REBUILD_REQUIRED');
    });

    it('checkpoint replay 가능 범위는 earliest와 high-watermark를 모두 포함한다', () => {
        const target = { partition: 0, low: '3', high: '10', offset: '10' };

        expect(isCheckpointWithinRetainedRange('3', target)).toBe(true);
        expect(isCheckpointWithinRetainedRange('10', target)).toBe(true);
        expect(isCheckpointWithinRetainedRange('2', target)).toBe(false);
        expect(isCheckpointWithinRetainedRange('11', target)).toBe(false);
    });

    it('dataset과 configured source generation이 다르면 checkpoint를 seek하지 않는다', async () => {
        const harness = createHarness(
            [{ partition: 0, low: '0', high: '10', offset: '10' }],
            [{ partition: 0, offset: '10', snapshotCompletedOffset: '9' }],
        );
        harness.setDataset('channel', {
            stream: 'channel',
            groupId: PROJECTION_STREAMS.channel.groupId,
            topic: PROJECTION_STREAMS.channel.topic,
            sourceGeneration: 'old-source-generation',
            datasetGeneration: 'dataset-generation-1',
            mode: 'INCREMENTAL',
            status: 'ACTIVE',
            activationDocumentCount: 0,
            baseOffsets: [{ partition: 0, offset: '0' }],
            targetOffsets: [{ partition: 0, offset: '10' }],
        });
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);

        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);

        expect(harness.seek).not.toHaveBeenCalled();
        expect(service.getDetailedStatus().channel).toEqual(expect.objectContaining({
            status: 'REBUILD_REQUIRED',
            reason: expect.stringContaining('source generation mismatch'),
        }));
    });

    it('명시적 rebuild는 projection을 초기화하고 fresh snapshot 뒤에만 dataset을 활성화한다', async () => {
        const harness = createHarness(
            [{ partition: 0, low: '0', high: '1', offset: '1' }],
            [{ partition: 0, offset: '1', snapshotCompletedOffset: '0' }],
        );
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);
        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await harness.activate(PROJECTION_STREAMS.channel, [0]);

        const requested = await service.requestRebuild('channel', 'operator requested rebuild');

        expect(requested.status).toBe('REBUILDING');
        expect(harness.resetProjection).toHaveBeenCalledWith('channel');
        expect(harness.seek).toHaveBeenLastCalledWith({
            topic: PROJECTION_STREAMS.channel.topic,
            partition: 0,
            offset: '0',
        });
        expect(service.getStatus().channel).toBe(false);

        await service.processMessage(PROJECTION_STREAMS.channel, 0, '0', () => Promise.resolve({
            snapshotBarrier: {
                offset: '0',
                snapshotId: '93b19168-4a63-49cd-b01d-b8d0667a1cb5',
                source: 'cowork-channel',
                occurredAt: new Date('2026-08-26T11:00:01Z'),
            },
        }));
        await service.checkCatchup();

        expect(service.getStatus().channel).toBe(true);
        expect(service.getDetailedStatus().channel?.status).toBe('ACTIVE');
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
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);
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
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);
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
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);
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
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);
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

    it('ready 후 runtime topic 재생성을 감지하면 해당 stream에 명시적 rebuild를 요구한다', async () => {
        const harness = createHarness(
            [{ partition: 0, low: '0', high: '10', offset: '10' }],
            [{ partition: 0, offset: '10', snapshotCompletedOffset: '9' }],
        );
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);
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

        harness.admin.fetchTopicOffsets.mockResolvedValue([
            { partition: 0, low: '0', high: '2', offset: '2' },
        ]);

        await service.checkCatchup();

        expect(service.getStatus().channel).toBe(false);
        expect(service.getDetailedStatus().channel).toEqual(expect.objectContaining({
            status: 'REBUILD_REQUIRED',
            reason: expect.stringContaining('outside the retained log'),
        }));
    });

    it('ready 후 snapshot marker receipt가 소실되면 readiness를 다시 닫는다', async () => {
        const harness = createHarness(
            [{ partition: 0, low: '0', high: '10', offset: '10' }],
            [{ partition: 0, offset: '10', snapshotCompletedOffset: '9' }],
        );
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);
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
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);
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
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);
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
    it('projection마다 전용 admin 커넥션을 연결한다', async () => {
        const harness = createHarness([{ partition: 0, low: '0', high: '0', offset: '0' }]);
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);

        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channelMember);

        expect(harness.kafkaAdmin).toHaveBeenCalledTimes(2);
        expect(harness.adminInstances).toHaveLength(2);
        expect(harness.adminInstances[0].connect).toHaveBeenCalledTimes(1);
        expect(harness.adminInstances[1].connect).toHaveBeenCalledTimes(1);
    });

    it('한 stream의 admin 요청이 멈춰도 다른 stream 등록은 진행된다', async () => {
        jest.useFakeTimers();
        const harness = createHarness([{ partition: 0, low: '0', high: '0', offset: '0' }]);
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);
        harness.admin.fetchTopicOffsets.mockImplementation((topic: string) => (
            topic === PROJECTION_STREAMS.teamRole.topic
                ? new Promise(() => {})
                : Promise.resolve([{ partition: 0, low: '0', high: '0', offset: '0' }])
        ));

        void service
            .registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.teamRole)
            .catch(() => undefined);
        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channelMember);

        expect(service.getDetailedStatus().channelMember).toBeDefined();
        expect(service.getDetailedStatus().teamRole).toBeUndefined();
    });

    it('admin 요청이 상한을 넘기면 커넥션을 폐기하고 다음 호출에서 재연결한다', async () => {
        jest.useFakeTimers();
        const harness = createHarness([{ partition: 0, low: '0', high: '0', offset: '0' }]);
        service = new ProjectionReadinessService(harness.checkpoints, harness.datasets, harness.metrics);
        harness.admin.fetchTopicOffsets.mockReturnValueOnce(new Promise(() => {}));

        const registration = service
            .registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);
        const rejected = expect(registration).rejects.toThrow('timed out');
        await jest.advanceTimersByTimeAsync(ADMIN_REQUEST_TIMEOUT_MS);
        await rejected;

        expect(harness.adminInstances[0].disconnect).toHaveBeenCalledTimes(1);

        await service.registerProjection(harness.kafka, harness.consumer, PROJECTION_STREAMS.channel);

        expect(harness.kafkaAdmin).toHaveBeenCalledTimes(2);
        expect(service.getDetailedStatus().channel).toBeDefined();
    });
});
