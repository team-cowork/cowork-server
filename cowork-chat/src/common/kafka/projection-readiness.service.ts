import { Injectable, Logger, OnModuleDestroy } from '@nestjs/common';
import {
    Admin,
    Consumer,
    ConsumerGroupJoinEvent,
    ConsumerHeartbeatEvent,
    Kafka,
    PartitionOffset,
} from 'kafkajs';
import {
    ProjectionAssignmentLease,
    ProjectionCheckpointFenceError,
    ProjectionCheckpointOffset,
    ProjectionCheckpointRepository,
    SnapshotBarrierReceipt,
} from './projection-checkpoint.repository';

export interface ProjectionStream {
    name: string;
    topic: string;
    groupId: string;
    expectedSource: string;
}

export interface StartupPartitionOffset extends PartitionOffset {
    low: string;
    high: string;
}

export const PROJECTION_STREAMS = {
    channel: {
        name: 'channel',
        topic: 'channel.event',
        groupId: 'cowork-chat-channel-event-v2-projection',
        expectedSource: 'cowork-channel',
    },
    project: {
        name: 'project',
        topic: 'project.event',
        groupId: 'cowork-chat-project-event-v2-projection',
        expectedSource: 'cowork-project',
    },
    channelMember: {
        name: 'channelMember',
        topic: 'channel.member.event',
        groupId: 'cowork-chat-membership-v2-projection',
        expectedSource: 'cowork-channel',
    },
    projectMember: {
        name: 'projectMember',
        topic: 'project.member.event',
        groupId: 'cowork-chat-project-member-event-v2-projection',
        expectedSource: 'cowork-project',
    },
    projectGithubRepo: {
        name: 'projectGithubRepo',
        topic: 'project.github-repo.event',
        groupId: 'cowork-chat-project-github-repo-event-v1-projection',
        expectedSource: 'cowork-project',
    },
    teamMember: {
        name: 'teamMember',
        topic: 'team.member.event',
        groupId: 'cowork-chat-team-member-event-v1-projection',
        expectedSource: 'cowork-team',
    },
    userProfile: {
        name: 'userProfile',
        topic: 'user.profile.event',
        groupId: 'cowork-chat-user-profile-event-v1-projection',
        expectedSource: 'cowork-user',
    },
} as const satisfies Record<string, ProjectionStream>;

type ProjectionName = keyof typeof PROJECTION_STREAMS;

interface PendingProjectionAssignment {
    revision: number;
    memberId: string;
    assignedPartitions: number[];
    afterHeartbeatSequence: number;
    groupGenerationId?: number;
    claimInFlight: boolean;
}

interface ProjectionCatchupState {
    stream: ProjectionStream;
    consumer: Consumer;
    targets: StartupPartitionOffset[];
    checkpoints: Map<number, string>;
    checkpointLeases: Map<number, ProjectionAssignmentLease>;
    snapshotBarriers: Map<number, string>;
    invalidRecordOffsets: Map<number, string>;
    localAssignmentLeases: Map<number, ProjectionAssignmentLease>;
    pendingAssignment?: PendingProjectionAssignment;
    heartbeatSequence: number;
    renewalInFlight: boolean;
    leaseRenewalPaused: boolean;
    assignmentObserved: boolean;
    assignmentRevision: number;
    assignmentTask: Promise<void>;
    ready: boolean;
}

const POLL_INTERVAL_MS = 1_000;

function isSameLease(
    checkpoint: ProjectionCheckpointOffset | undefined,
    lease: ProjectionAssignmentLease,
): boolean {
    return checkpoint?.assignmentEpoch === lease.assignmentEpoch
        && checkpoint.assignmentMemberId === lease.memberId
        && checkpoint.assignmentGenerationId === lease.groupGenerationId;
}

/** Kafka offset은 JavaScript 안전 정수 범위를 넘을 수 있으므로 항상 BigInt로 비교한다. */
function compareOffsets(left: string, right: string): number {
    const leftOffset = BigInt(left);
    const rightOffset = BigInt(right);
    if (leftOffset === rightOffset) return 0;
    return leftOffset > rightOffset ? 1 : -1;
}

/** 저장된 checkpoint가 Kafka에 남아 있는 replay 가능 범위 안인지 판정한다. */
export function isCheckpointWithinRetainedRange(
    checkpoint: string,
    target: StartupPartitionOffset,
): boolean {
    return compareOffsets(checkpoint, target.low) >= 0
        && compareOffsets(checkpoint, target.high) <= 0;
}

/** 시작 시 캡처한 파티션별 high-watermark까지 shared checkpoint가 도달했는지 판정한다. */
export function hasReachedStartupOffsets(
    targets: StartupPartitionOffset[],
    checkpoints: PartitionOffset[],
): boolean {
    const checkpointByPartition = new Map(
        checkpoints.map(({ partition, offset }) => [partition, offset]),
    );

    return targets.every((target) => {
        const checkpoint = checkpointByPartition.get(target.partition);
        if (checkpoint === undefined) return false;
        try {
            return compareOffsets(checkpoint, target.high) >= 0;
        } catch {
            return false;
        }
    });
}

/** 모든 partition에서 full snapshot marker까지 checkpoint가 전진했는지 판정한다. */
export function hasCompletedSnapshotBarriers(
    targets: StartupPartitionOffset[],
    checkpoints: ProjectionCheckpointOffset[],
): boolean {
    const checkpointByPartition = new Map(checkpoints.map((checkpoint) => [checkpoint.partition, checkpoint]));
    return targets.every(({ partition }) => {
        const checkpoint = checkpointByPartition.get(partition);
        if (!checkpoint?.assignmentEpoch
            || !checkpoint.assignmentMemberId
            || checkpoint.assignmentGenerationId === undefined
            || !checkpoint.snapshotCompletedOffset
            || checkpoint.invalidRecordOffset !== undefined) return false;
        try {
            const target = targets.find((candidate) => candidate.partition === partition);
            return target !== undefined
                && compareOffsets(checkpoint.snapshotCompletedOffset, target.low) >= 0
                && compareOffsets(checkpoint.offset, checkpoint.snapshotCompletedOffset) > 0;
        } catch {
            return false;
        }
    });
}

/**
 * Kafka projection consumer들의 shared Mongo replay barrier.
 *
 * consumer가 실행되기 직전에 토픽의 high-watermark와 Mongo checkpoint를 캡처하고, group
 * assignment 때 broker group offset 대신 checkpoint(없으면 earliest)로 seek한다. projection
 * 반영 뒤 checkpoint를 저장하므로 Mongo가 초기화된 환경에서도 기존 broker group commit이
 * 이벤트 replay를 건너뛰지 못한다.
 */
@Injectable()
export class ProjectionReadinessService implements OnModuleDestroy {
    private readonly logger = new Logger(ProjectionReadinessService.name);
    private readonly states = new Map<ProjectionName, ProjectionCatchupState>();
    private readonly registrations = new Map<ProjectionName, Promise<void>>();
    private admin?: Admin;
    private adminPromise?: Promise<Admin>;
    private checkPromise?: Promise<void>;
    private pollTimer?: NodeJS.Timeout;
    private stopped = false;
    private fatalInvariantReason?: string;
    private readonly fatalInvariantListeners = new Set<(reason: string) => void>();
    private resolveReady!: () => void;
    private readyPromise: Promise<void>;

    constructor(private readonly checkpoints: ProjectionCheckpointRepository) {
        this.readyPromise = this.createReadyPromise();
    }

    async registerProjection(kafka: Kafka, consumer: Consumer, stream: ProjectionStream): Promise<void> {
        const name = this.validateStream(stream);
        const existing = this.registrations.get(name);
        if (existing) return existing;

        const registration = this.captureStartupState(kafka, consumer, name, stream).catch((error: unknown) => {
            this.registrations.delete(name);
            throw error;
        });
        this.registrations.set(name, registration);
        return registration;
    }

    /** projection 처리 성공 뒤 next offset을 shared Mongo에 기록한다. */
    async processMessage(
        stream: ProjectionStream,
        partition: number,
        offset: string,
        applyProjection: () => Promise<{ snapshotBarrier?: SnapshotBarrierReceipt } | void>,
    ): Promise<void> {
        const name = this.validateStream(stream);
        const state = this.states.get(name);
        if (!state) throw new Error(`Kafka projection stream is not registered: ${stream.name}`);

        await state.assignmentTask;
        const assignmentLease = state.localAssignmentLeases.get(partition);
        if (!assignmentLease) {
            throw new Error(
                `Kafka projection partition is not owned by the current assignment: ${stream.topic}[${partition}]`,
            );
        }

        const result = await applyProjection();
        if (state.localAssignmentLeases.get(partition) !== assignmentLease) {
            throw new Error(`Kafka projection assignment changed while applying: ${stream.topic}[${partition}]`);
        }
        const nextOffset = (BigInt(offset) + 1n).toString();
        try {
            if (result?.snapshotBarrier) {
                await this.checkpoints.advance(
                    stream.groupId,
                    stream.topic,
                    partition,
                    assignmentLease,
                    nextOffset,
                    result.snapshotBarrier,
                );
            } else {
                await this.checkpoints.advance(
                    stream.groupId,
                    stream.topic,
                    partition,
                    assignmentLease,
                    nextOffset,
                );
            }
        } catch (error) {
            if (error instanceof ProjectionCheckpointFenceError) {
                this.markFatalInvariantViolation(
                    `Kafka projection checkpoint advance was fenced: ${stream.topic}[${partition}]`,
                );
            }
            throw error;
        }
        if (state.localAssignmentLeases.get(partition) !== assignmentLease) {
            throw new Error(`Kafka projection assignment changed after checkpoint: ${stream.topic}[${partition}]`);
        }

        const current = state.checkpoints.get(partition);
        if (current === undefined || compareOffsets(nextOffset, current) > 0) {
            state.checkpoints.set(partition, nextOffset);
        }
        state.checkpointLeases.set(partition, assignmentLease);
        if (result?.snapshotBarrier) {
            state.snapshotBarriers.set(partition, result.snapshotBarrier.offset);
        }
        // assignment에서 readiness를 닫은 뒤에는 fresh broker high-watermark를
        // 다시 읽는 poll만 ready를 열 수 있다.
        this.states.set(name, state);
        this.schedulePoll();
    }

    /** malformed record 원문을 먼저 영속화한다. 실패하면 processMessage도 실패해 offset이 유지된다. */
    async quarantine(
        stream: ProjectionStream,
        partition: number,
        offset: string,
        eventKey: string | null,
        payload: string | null,
        reason: string,
    ): Promise<void> {
        const name = this.validateStream(stream);
        const state = this.states.get(name);
        if (!state) throw new Error(`Kafka projection stream is not registered: ${stream.name}`);
        await state.assignmentTask;
        const assignmentLease = state.localAssignmentLeases.get(partition);
        if (!assignmentLease) {
            throw new Error(
                `Kafka projection partition is not owned by the current assignment: ${stream.topic}[${partition}]`,
            );
        }
        this.closeStreamReadiness(name, state);
        await this.checkpoints.quarantine(
            stream.groupId,
            stream.topic,
            partition,
            offset,
            eventKey,
            payload,
            reason,
        );
        if (state.localAssignmentLeases.get(partition) !== assignmentLease) {
            throw new Error(`Kafka projection assignment changed while quarantining: ${stream.topic}[${partition}]`);
        }
        try {
            await this.checkpoints.markInvalidRecord(
                stream.groupId,
                stream.topic,
                partition,
                assignmentLease,
                offset,
            );
        } catch (error) {
            if (error instanceof ProjectionCheckpointFenceError) {
                this.markFatalInvariantViolation(
                    `Kafka projection invalid-record latch was fenced: ${stream.topic}[${partition}]`,
                );
            }
            throw error;
        }
        if (state.localAssignmentLeases.get(partition) !== assignmentLease) {
            throw new Error(`Kafka projection assignment changed after quarantine latch: ${stream.topic}[${partition}]`);
        }
        state.invalidRecordOffsets.set(partition, offset);
        this.logger.warn(
            `Kafka projection record quarantined: ${stream.topic}[${partition}]@${offset} reason=${reason}`,
        );
    }

    isReady(): boolean {
        return Object.keys(PROJECTION_STREAMS).every((name) => this.states.get(name as ProjectionName)?.ready === true);
    }

    whenReady(): Promise<void> {
        return this.isReady() ? Promise.resolve() : this.readyPromise;
    }

    onFatalInvariantViolation(listener: (reason: string) => void): () => void {
        this.fatalInvariantListeners.add(listener);
        if (this.fatalInvariantReason) listener(this.fatalInvariantReason);
        return () => this.fatalInvariantListeners.delete(listener);
    }

    getStatus(): Record<ProjectionName, boolean> {
        return Object.fromEntries(
            Object.keys(PROJECTION_STREAMS).map((name) => [name, this.states.get(name as ProjectionName)?.ready === true]),
        ) as Record<ProjectionName, boolean>;
    }

    /** shared checkpoint를 즉시 다시 읽어 다중 replica의 진행 상황을 반영한다. */
    checkCatchup(): Promise<void> {
        if (this.stopped) return Promise.resolve();
        if (this.checkPromise) return this.checkPromise;

        this.checkPromise = this.doCheckCatchup().finally(() => {
            this.checkPromise = undefined;
            this.schedulePoll();
        });
        return this.checkPromise;
    }

    async onModuleDestroy(): Promise<void> {
        this.stopped = true;
        if (this.pollTimer) clearTimeout(this.pollTimer);

        await Promise.all([...this.states.values()].map(async (state) => {
            state.assignmentRevision += 1;
            state.pendingAssignment = undefined;
            state.assignmentObserved = false;
            const owned = [...state.localAssignmentLeases];
            state.localAssignmentLeases.clear();
            await state.assignmentTask.catch(() => undefined);
            await this.releaseAssignments(state, owned).catch(() => undefined);
            await this.releaseAssignments(state, [...state.localAssignmentLeases]).catch(() => undefined);
            state.localAssignmentLeases.clear();
        }));

        if (this.adminPromise) {
            await this.adminPromise
                .then((admin) => admin.disconnect())
                .catch(() => undefined);
        }
    }

    private async captureStartupState(
        kafka: Kafka,
        consumer: Consumer,
        name: ProjectionName,
        stream: ProjectionStream,
    ): Promise<void> {
        const admin = await this.getAdmin(kafka);
        const targets = await admin.fetchTopicOffsets(stream.topic);
        if (targets.length === 0) {
            throw new Error(`Kafka projection topic has no partitions: ${stream.topic}`);
        }
        const state: ProjectionCatchupState = {
            stream,
            consumer,
            targets,
            checkpoints: new Map(),
            checkpointLeases: new Map(),
            snapshotBarriers: new Map(),
            invalidRecordOffsets: new Map(),
            localAssignmentLeases: new Map(),
            heartbeatSequence: 0,
            renewalInFlight: false,
            leaseRenewalPaused: false,
            assignmentObserved: false,
            assignmentRevision: 0,
            assignmentTask: Promise.resolve(),
            ready: false,
        };
        this.states.set(name, state);
        this.attachAssignmentFence(consumer, name, state);
        this.logger.log(
            `Projection startup offsets captured: ${stream.topic} `
            + `[${targets.map(({ partition, low, high }) => `${partition}:${low}-${high}`).join(', ')}]`,
        );
        this.schedulePoll();
    }

    private attachAssignmentFence(
        consumer: Consumer,
        name: ProjectionName,
        state: ProjectionCatchupState,
    ): void {
        consumer.on(consumer.events.GROUP_JOIN, (event: ConsumerGroupJoinEvent) => {
            if (this.stopped || event.payload.groupId !== state.stream.groupId) return;
            const assignedPartitions = event.payload.memberAssignment[state.stream.topic] ?? [];
            const revision = state.assignmentRevision + 1;
            const previouslyOwned = [...state.localAssignmentLeases];
            state.assignmentRevision = revision;
            state.assignmentObserved = false;
            state.leaseRenewalPaused = false;
            state.localAssignmentLeases.clear();
            state.pendingAssignment = {
                revision,
                memberId: event.payload.memberId,
                assignedPartitions: [...assignedPartitions],
                afterHeartbeatSequence: state.heartbeatSequence,
                claimInFlight: false,
            };
            for (const partition of new Set([
                ...previouslyOwned.map(([ownedPartition]) => ownedPartition),
                ...assignedPartitions,
            ])) {
                state.checkpoints.delete(partition);
                state.checkpointLeases.delete(partition);
                state.snapshotBarriers.delete(partition);
                state.invalidRecordOffsets.delete(partition);
            }
            this.closeStreamReadiness(name, state);

            try {
                if (assignedPartitions.length > 0) {
                    consumer.pause([{ topic: state.stream.topic, partitions: assignedPartitions }]);
                }
            } catch (error) {
                this.markFatalInvariantViolation(
                    `Kafka projection assignment pause failed: ${state.stream.topic}: `
                    + `${error instanceof Error ? error.message : String(error)}`,
                );
                return;
            }

            this.enqueueAssignmentTask(state, () => this.releaseAssignments(state, previouslyOwned), (error) => {
                this.closeStreamReadiness(name, state);
                this.logger.warn(
                    `Kafka projection previous lease release failed [topic=${state.stream.topic}]: `
                    + `${error instanceof Error ? error.message : String(error)}`,
                );
            });
        });

        consumer.on(consumer.events.HEARTBEAT, (event: ConsumerHeartbeatEvent) => {
            this.handleHeartbeat(name, state, event);
        });
        consumer.on(consumer.events.REBALANCING, () => {
            this.revokeAssignments(name, state, 'rebalance');
        });
        consumer.on(consumer.events.DISCONNECT, () => {
            this.revokeAssignments(name, state, 'disconnect');
        });
        consumer.on(consumer.events.STOP, () => {
            this.revokeAssignments(name, state, 'stop');
        });
        consumer.on(consumer.events.CRASH, () => {
            this.revokeAssignments(name, state, 'crash');
        });
    }

    private handleHeartbeat(
        name: ProjectionName,
        state: ProjectionCatchupState,
        event: ConsumerHeartbeatEvent,
    ): void {
        if (this.stopped || event.payload.groupId !== state.stream.groupId) return;
        state.heartbeatSequence += 1;
        const heartbeatSequence = state.heartbeatSequence;
        const pending = state.pendingAssignment;
        if (pending) {
            if (heartbeatSequence <= pending.afterHeartbeatSequence
                || event.payload.memberId !== pending.memberId) return;
            if (pending.groupGenerationId !== undefined
                && pending.groupGenerationId !== event.payload.groupGenerationId) return;
            pending.groupGenerationId = event.payload.groupGenerationId;
            if (pending.claimInFlight) return;
            pending.claimInFlight = true;
            this.enqueueAssignmentTask(state, async () => {
                try {
                    await this.claimAssignedPartitions(state, pending);
                } finally {
                    if (state.pendingAssignment === pending) pending.claimInFlight = false;
                }
            }, (error) => {
                this.closeStreamReadiness(name, state);
                if (state.pendingAssignment === pending) pending.claimInFlight = false;
                const message = error instanceof Error ? error.message : String(error);
                if (message.includes('partition topology changed')
                    || message.includes('assigned partition is missing')
                    || message.includes('assignment activation failed')) {
                    this.markFatalInvariantViolation(
                        `Kafka projection assignment claim failed: ${state.stream.topic}: ${message}`,
                    );
                } else {
                    this.logger.warn(
                        `Kafka projection assignment claim failed [topic=${state.stream.topic}]: ${message}`,
                    );
                }
            });
            return;
        }

        const owned = [...state.localAssignmentLeases];
        if (owned.length === 0 || state.renewalInFlight) return;
        const currentLease = owned[0][1];
        if (event.payload.memberId !== currentLease.memberId) return;
        if (event.payload.groupGenerationId !== currentLease.groupGenerationId) {
            this.revokeAssignments(name, state, 'heartbeat generation change');
            return;
        }
        state.renewalInFlight = true;
        const revision = state.assignmentRevision;
        this.enqueueAssignmentTask(state, async () => {
            try {
                await Promise.all(owned.map(([partition, lease]) => this.checkpoints.renewAssignment(
                    state.stream.groupId,
                    state.stream.topic,
                    partition,
                    lease,
                )));
                if (revision !== state.assignmentRevision
                    || owned.some(([partition, lease]) => state.localAssignmentLeases.get(partition) !== lease)) return;
                if (state.leaseRenewalPaused) {
                    state.consumer.resume([{
                        topic: state.stream.topic,
                        partitions: owned.map(([partition]) => partition),
                    }]);
                    state.leaseRenewalPaused = false;
                }
                this.schedulePoll();
            } finally {
                state.renewalInFlight = false;
            }
        }, (error) => {
            state.renewalInFlight = false;
            this.closeStreamReadiness(name, state);
            const partitions = [...state.localAssignmentLeases.keys()];
            try {
                if (partitions.length > 0) {
                    state.consumer.pause([{ topic: state.stream.topic, partitions }]);
                    state.leaseRenewalPaused = true;
                }
            } catch (pauseError) {
                this.markFatalInvariantViolation(
                    `Kafka projection lease-loss pause failed: ${state.stream.topic}: `
                    + `${pauseError instanceof Error ? pauseError.message : String(pauseError)}`,
                );
                return;
            }
            const message = error instanceof Error ? error.message : String(error);
            if (error instanceof ProjectionCheckpointFenceError) {
                this.markFatalInvariantViolation(
                    `Kafka projection lease renewal was fenced: ${state.stream.topic}: ${message}`,
                );
            } else {
                this.logger.warn(
                    `Kafka projection lease renewal failed [topic=${state.stream.topic}]: ${message}`,
                );
            }
        });
    }

    private async claimAssignedPartitions(
        state: ProjectionCatchupState,
        pending: PendingProjectionAssignment,
    ): Promise<void> {
        if (state.pendingAssignment !== pending
            || pending.revision !== state.assignmentRevision
            || pending.groupGenerationId === undefined) return;
        const currentOffsets = await this.requireAdmin().fetchTopicOffsets(state.stream.topic);
        if (state.pendingAssignment !== pending || pending.revision !== state.assignmentRevision) return;
        const startupPartitions = state.targets.map(({ partition }) => partition).sort((a, b) => a - b);
        const currentPartitions = currentOffsets.map(({ partition }) => partition).sort((a, b) => a - b);
        if (startupPartitions.join(',') !== currentPartitions.join(',')) {
            throw new Error(
                `partition topology changed: startup=${startupPartitions.join(',')} `
                + `current=${currentPartitions.join(',')}`,
            );
        }
        const currentByPartition = new Map(currentOffsets.map((offset) => [offset.partition, offset]));
        const claimed: Array<{
            partition: number;
            target: StartupPartitionOffset;
            lease: ProjectionAssignmentLease;
        }> = [];
        try {
            for (const partition of pending.assignedPartitions) {
                if (state.pendingAssignment !== pending || pending.revision !== state.assignmentRevision) {
                    await this.releaseAssignments(
                        state,
                        claimed.map(({ partition: claimedPartition, lease }) => [claimedPartition, lease]),
                    );
                    return;
                }
                const target = currentByPartition.get(partition);
                if (!target) throw new Error(`assigned partition is missing from broker metadata: ${partition}`);
                const lease = await this.checkpoints.claimForAssignment(
                    state.stream.groupId,
                    state.stream.topic,
                    partition,
                    target.low,
                    pending.memberId,
                    pending.groupGenerationId,
                );
                if (!lease) {
                    await this.releaseAssignments(
                        state,
                        claimed.map(({ partition: claimedPartition, lease: claimedLease }) => (
                            [claimedPartition, claimedLease]
                        )),
                    );
                    this.logger.warn(
                        `Kafka projection assignment lease is still active; retrying after heartbeat: `
                        + `${state.stream.topic}[${partition}]`,
                    );
                    return;
                }
                claimed.push({ partition, target, lease });
            }
        } catch (error) {
            await this.releaseAssignments(
                state,
                claimed.map(({ partition, lease }) => [partition, lease]),
            ).catch(() => undefined);
            throw error;
        }

        if (state.pendingAssignment !== pending || pending.revision !== state.assignmentRevision) {
            await this.releaseAssignments(
                state,
                claimed.map(({ partition, lease }) => [partition, lease]),
            );
            return;
        }
        state.targets = currentOffsets;
        state.assignmentObserved = true;
        state.pendingAssignment = undefined;
        try {
            for (const { partition, target, lease } of claimed) {
                state.localAssignmentLeases.set(partition, lease);
                state.checkpoints.set(partition, target.low);
                state.checkpointLeases.set(partition, lease);
                state.snapshotBarriers.delete(partition);
                state.invalidRecordOffsets.delete(partition);
                state.consumer.seek({ topic: state.stream.topic, partition, offset: target.low });
                this.logger.log(
                    `Projection assignment fenced and reset: ${state.stream.topic}[${partition}] -> ${target.low}`,
                );
            }
            if (pending.assignedPartitions.length > 0) {
                state.consumer.resume([{ topic: state.stream.topic, partitions: pending.assignedPartitions }]);
            }
        } catch (error) {
            state.assignmentObserved = false;
            for (const { partition } of claimed) {
                state.localAssignmentLeases.delete(partition);
                state.checkpoints.delete(partition);
                state.checkpointLeases.delete(partition);
                state.snapshotBarriers.delete(partition);
                state.invalidRecordOffsets.delete(partition);
            }
            await this.releaseAssignments(
                state,
                claimed.map(({ partition, lease }) => [partition, lease]),
            ).catch(() => undefined);
            throw new Error(
                `assignment activation failed: ${error instanceof Error ? error.message : String(error)}`,
                { cause: error },
            );
        }
        this.schedulePoll();
    }

    private revokeAssignments(
        name: ProjectionName,
        state: ProjectionCatchupState,
        reason: string,
    ): void {
        if (this.stopped) return;
        const owned = [...state.localAssignmentLeases];
        state.assignmentRevision += 1;
        state.pendingAssignment = undefined;
        state.assignmentObserved = false;
        state.leaseRenewalPaused = false;
        state.localAssignmentLeases.clear();
        for (const [partition] of owned) {
            state.checkpoints.delete(partition);
            state.checkpointLeases.delete(partition);
            state.snapshotBarriers.delete(partition);
            state.invalidRecordOffsets.delete(partition);
        }
        this.closeStreamReadiness(name, state);
        this.enqueueAssignmentTask(state, () => this.releaseAssignments(state, owned), (error) => {
            this.logger.warn(
                `Kafka projection lease release failed [topic=${state.stream.topic}, reason=${reason}]: `
                + `${error instanceof Error ? error.message : String(error)}`,
            );
        });
    }

    private async releaseAssignments(
        state: ProjectionCatchupState,
        assignments: Array<[number, ProjectionAssignmentLease]>,
    ): Promise<void> {
        await Promise.all(assignments.map(([partition, lease]) => this.checkpoints.releaseAssignment(
            state.stream.groupId,
            state.stream.topic,
            partition,
            lease,
        )));
    }

    private enqueueAssignmentTask(
        state: ProjectionCatchupState,
        task: () => Promise<void>,
        onError: (error: unknown) => void,
    ): void {
        const queued = state.assignmentTask.catch(() => undefined).then(task);
        state.assignmentTask = queued.catch(onError);
    }

    private getAdmin(kafka: Kafka): Promise<Admin> {
        if (!this.adminPromise) {
            this.admin = kafka.admin();
            this.adminPromise = this.admin.connect().then(() => this.admin as Admin);
        }
        return this.adminPromise;
    }

    private async doCheckCatchup(): Promise<void> {
        await Promise.all([...this.states.entries()].map(async ([name, state]) => {
            try {
                if (!state.assignmentObserved) {
                    this.closeStreamReadiness(name, state);
                    return;
                }
                const assignmentRevision = state.assignmentRevision;
                await state.assignmentTask;
                if (assignmentRevision !== state.assignmentRevision) return;
                const storedCheckpoints = await this.checkpoints.find(state.stream.groupId, state.stream.topic);
                if (assignmentRevision !== state.assignmentRevision) return;
                const currentOffsets = await this.requireAdmin().fetchTopicOffsets(state.stream.topic);
                if (assignmentRevision !== state.assignmentRevision) return;
                const currentByPartition = new Map(currentOffsets.map((offset) => [offset.partition, offset]));
                for (const checkpoint of storedCheckpoints) {
                    const current = currentByPartition.get(checkpoint.partition);
                    if (!current || !isCheckpointWithinRetainedRange(checkpoint.offset, current)) {
                        this.markFatalInvariantViolation(
                            `Kafka projection checkpoint is outside the retained log: `
                            + `${state.stream.topic}[${checkpoint.partition}] checkpoint=${checkpoint.offset} `
                            + `retained=${current?.low ?? 'missing'}-${current?.high ?? 'missing'}`,
                        );
                        return;
                    }
                }
                const storedByPartition = new Map(
                    storedCheckpoints.map((checkpoint) => [checkpoint.partition, checkpoint]),
                );
                for (const [partition, lease] of state.localAssignmentLeases) {
                    if (!isSameLease(storedByPartition.get(partition), lease)) {
                        this.markFatalInvariantViolation(
                            `Kafka projection assignment fence was superseded: `
                            + `${state.stream.topic}[${partition}]`,
                        );
                        return;
                    }
                }
                const startupPartitions = state.targets.map(({ partition }) => partition).sort((a, b) => a - b);
                const currentPartitions = currentOffsets.map(({ partition }) => partition).sort((a, b) => a - b);
                if (startupPartitions.join(',') !== currentPartitions.join(',')) {
                    this.markFatalInvariantViolation(
                        `Kafka projection partition topology changed: ${state.stream.topic} `
                        + `startup=${startupPartitions.join(',')} current=${currentPartitions.join(',')}`,
                    );
                    return;
                }
                state.targets = currentOffsets;
                state.checkpoints = new Map(storedCheckpoints.map(({ partition, offset }) => [partition, offset]));
                state.checkpointLeases = new Map(
                    storedCheckpoints.flatMap(({
                        partition,
                        assignmentEpoch,
                        assignmentMemberId,
                        assignmentGenerationId,
                    }) => (
                        assignmentEpoch
                            && assignmentMemberId
                            && assignmentGenerationId !== undefined
                            ? [[partition, {
                                assignmentEpoch,
                                memberId: assignmentMemberId,
                                groupGenerationId: assignmentGenerationId,
                            }] as const]
                            : []
                    )),
                );
                state.snapshotBarriers = new Map(
                    storedCheckpoints.flatMap(({ partition, snapshotCompletedOffset }) => (
                        snapshotCompletedOffset ? [[partition, snapshotCompletedOffset] as const] : []
                    )),
                );
                state.invalidRecordOffsets = new Map(
                    storedCheckpoints.flatMap(({ partition, invalidRecordOffset }) => (
                        invalidRecordOffset ? [[partition, invalidRecordOffset] as const] : []
                    )),
                );
                this.updateReady(name, state);
            } catch (error) {
                const message = error instanceof Error ? error.message : String(error);
                this.closeStreamReadiness(name, state);
                this.logger.warn(`Kafka projection catch-up check failed [topic=${state.stream.topic}]: ${message}`);
            }
        }));
    }

    private updateReady(name: ProjectionName, state: ProjectionCatchupState): void {
        const wasGloballyReady = this.isReady();
        const checkpointOffsets = [...state.checkpoints].map(([partition, offset]) => ({ partition, offset }));
        const checkpointState = checkpointOffsets.map((checkpoint) => ({
            ...checkpoint,
            assignmentEpoch: state.checkpointLeases.get(checkpoint.partition)?.assignmentEpoch,
            assignmentMemberId: state.checkpointLeases.get(checkpoint.partition)?.memberId,
            assignmentGenerationId: state.checkpointLeases.get(checkpoint.partition)?.groupGenerationId,
            snapshotCompletedOffset: state.snapshotBarriers.get(checkpoint.partition),
            invalidRecordOffset: state.invalidRecordOffsets.get(checkpoint.partition),
        }));
        const wasReady = state.ready;
        state.ready = this.fatalInvariantReason === undefined
            && state.assignmentObserved
            && !state.leaseRenewalPaused
            && hasReachedStartupOffsets(state.targets, checkpointOffsets)
            && hasCompletedSnapshotBarriers(state.targets, checkpointState);
        this.states.set(name, state);
        if (state.ready && !wasReady) {
            this.logger.log(`Kafka projection caught up: ${state.stream.topic}`);
        } else if (!state.ready && wasReady) {
            this.logger.warn(`Kafka projection readiness closed: ${state.stream.topic}`);
        }
        const globallyReady = this.isReady();
        if (globallyReady && !wasGloballyReady) {
            this.resolveReady();
        } else if (!globallyReady && wasGloballyReady) {
            this.readyPromise = this.createReadyPromise();
        }
    }

    private schedulePoll(): void {
        if (this.stopped || this.pollTimer) return;

        this.pollTimer = setTimeout(() => {
            this.pollTimer = undefined;
            void this.checkCatchup();
        }, POLL_INTERVAL_MS);
        this.pollTimer.unref();
    }

    private requireAdmin(): Admin {
        if (!this.admin) throw new Error('Kafka projection admin is not connected');
        return this.admin;
    }

    private markFatalInvariantViolation(reason: string): void {
        if (this.fatalInvariantReason) return;
        const wasGloballyReady = this.isReady();
        this.fatalInvariantReason = reason;
        for (const state of this.states.values()) state.ready = false;
        if (wasGloballyReady) this.readyPromise = this.createReadyPromise();
        this.logger.error(reason);
        for (const listener of this.fatalInvariantListeners) listener(reason);
    }

    private closeStreamReadiness(name: ProjectionName, state: ProjectionCatchupState): void {
        const wasGloballyReady = this.isReady();
        const wasReady = state.ready;
        state.ready = false;
        this.states.set(name, state);
        if (wasGloballyReady) this.readyPromise = this.createReadyPromise();
        if (wasReady) this.logger.warn(`Kafka projection readiness closed: ${state.stream.topic}`);
    }

    private createReadyPromise(): Promise<void> {
        return new Promise<void>((resolve) => {
            this.resolveReady = resolve;
        });
    }

    private validateStream(stream: ProjectionStream): ProjectionName {
        const name = stream.name as ProjectionName;
        const expected = PROJECTION_STREAMS[name];
        if (!expected
            || expected.topic !== stream.topic
            || expected.groupId !== stream.groupId
            || expected.expectedSource !== stream.expectedSource) {
            throw new Error(`Unknown Kafka projection stream: ${stream.name}`);
        }
        return name;
    }
}
