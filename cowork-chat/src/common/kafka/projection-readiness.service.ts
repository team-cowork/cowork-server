import { Injectable, Logger, OnModuleDestroy } from '@nestjs/common';
import { Admin, Consumer, ConsumerGroupJoinEvent, Kafka, PartitionOffset } from 'kafkajs';
import {
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

interface ProjectionCatchupState {
    stream: ProjectionStream;
    targets: StartupPartitionOffset[];
    checkpoints: Map<number, string>;
    snapshotBarriers: Map<number, string>;
    ready: boolean;
}

const POLL_INTERVAL_MS = 1_000;

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
        if (!checkpoint?.snapshotCompletedOffset) return false;
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
    private readonly readyPromise = new Promise<void>((resolve) => {
        this.resolveReady = resolve;
    });

    constructor(private readonly checkpoints: ProjectionCheckpointRepository) {}

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

        const result = await applyProjection();
        const nextOffset = (BigInt(offset) + 1n).toString();
        if (result?.snapshotBarrier) {
            await this.checkpoints.advance(
                stream.groupId,
                stream.topic,
                partition,
                nextOffset,
                result.snapshotBarrier,
            );
        } else {
            await this.checkpoints.advance(stream.groupId, stream.topic, partition, nextOffset);
        }

        const current = state.checkpoints.get(partition);
        if (current === undefined || compareOffsets(nextOffset, current) > 0) {
            state.checkpoints.set(partition, nextOffset);
        }
        if (result?.snapshotBarrier) {
            state.snapshotBarriers.set(partition, result.snapshotBarrier.offset);
        }
        this.updateReady(name, state);
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
        this.validateStream(stream);
        await this.checkpoints.quarantine(
            stream.groupId,
            stream.topic,
            partition,
            offset,
            eventKey,
            payload,
            reason,
        );
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
        // Checkpoints are read first so a subsequently captured broker high-watermark cannot be
        // older merely because another replica advanced while startup metadata was being fetched.
        const storedCheckpoints = await this.checkpoints.find(stream.groupId, stream.topic);
        const targets = await admin.fetchTopicOffsets(stream.topic);
        if (targets.length === 0) {
            throw new Error(`Kafka projection topic has no partitions: ${stream.topic}`);
        }

        const targetByPartition = new Map(targets.map((target) => [target.partition, target]));
        for (const checkpoint of storedCheckpoints) {
            const target = targetByPartition.get(checkpoint.partition);
            if (!target || !isCheckpointWithinRetainedRange(checkpoint.offset, target)) {
                throw new Error(
                    `Kafka projection checkpoint is outside the retained log: `
                    + `${stream.topic}[${checkpoint.partition}] checkpoint=${checkpoint.offset} `
                    + `retained=${target?.low ?? 'missing'}-${target?.high ?? 'missing'}`,
                );
            }
        }

        const checkpointMap = new Map(storedCheckpoints.map(({ partition, offset }) => [partition, offset]));
        const snapshotBarriers = new Map(
            storedCheckpoints.flatMap(({ partition, snapshotCompletedOffset }) => (
                snapshotCompletedOffset ? [[partition, snapshotCompletedOffset] as const] : []
            )),
        );
        const state: ProjectionCatchupState = {
            stream,
            targets,
            checkpoints: checkpointMap,
            snapshotBarriers,
            ready: false,
        };
        this.states.set(name, state);
        this.attachCheckpointSeek(consumer, state);
        this.logger.log(
            `Projection startup offsets captured: ${stream.topic} `
            + `[${targets.map(({ partition, low, high }) => `${partition}:${low}-${high}`).join(', ')}]`,
        );
        this.updateReady(name, state);
        this.schedulePoll();
    }

    private attachCheckpointSeek(consumer: Consumer, state: ProjectionCatchupState): void {
        consumer.on(consumer.events.GROUP_JOIN, (event: ConsumerGroupJoinEvent) => {
            const assignedPartitions = event.payload.memberAssignment[state.stream.topic] ?? [];
            for (const partition of assignedPartitions) {
                const target = state.targets.find((candidate) => candidate.partition === partition);
                if (!target) continue;

                const checkpoint = state.checkpoints.get(partition);
                const offset = checkpoint ?? target.low;
                consumer.seek({ topic: state.stream.topic, partition, offset });
                this.logger.log(
                    `Projection partition seek: ${state.stream.topic}[${partition}] -> ${offset}`,
                );
            }
        });
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
                const storedCheckpoints = await this.checkpoints.find(state.stream.groupId, state.stream.topic);
                const currentOffsets = await this.requireAdmin().fetchTopicOffsets(state.stream.topic);
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
                const startupPartitions = state.targets.map(({ partition }) => partition).sort((a, b) => a - b);
                const currentPartitions = currentOffsets.map(({ partition }) => partition).sort((a, b) => a - b);
                if (startupPartitions.join(',') !== currentPartitions.join(',')) {
                    this.markFatalInvariantViolation(
                        `Kafka projection partition topology changed: ${state.stream.topic} `
                        + `startup=${startupPartitions.join(',')} current=${currentPartitions.join(',')}`,
                    );
                    return;
                }
                state.checkpoints = new Map(storedCheckpoints.map(({ partition, offset }) => [partition, offset]));
                state.snapshotBarriers = new Map(
                    storedCheckpoints.flatMap(({ partition, snapshotCompletedOffset }) => (
                        snapshotCompletedOffset ? [[partition, snapshotCompletedOffset] as const] : []
                    )),
                );
                this.updateReady(name, state);
            } catch (error) {
                const message = error instanceof Error ? error.message : String(error);
                if (state.ready) {
                    state.ready = false;
                    this.states.set(name, state);
                    this.logger.warn(`Kafka projection readiness closed: ${state.stream.topic}`);
                }
                this.logger.warn(`Kafka projection catch-up check failed [topic=${state.stream.topic}]: ${message}`);
            }
        }));
    }

    private updateReady(name: ProjectionName, state: ProjectionCatchupState): void {
        const checkpointOffsets = [...state.checkpoints].map(([partition, offset]) => ({ partition, offset }));
        const checkpointState = checkpointOffsets.map((checkpoint) => ({
            ...checkpoint,
            snapshotCompletedOffset: state.snapshotBarriers.get(checkpoint.partition),
        }));
        const wasReady = state.ready;
        state.ready = this.fatalInvariantReason === undefined
            && hasReachedStartupOffsets(state.targets, checkpointOffsets)
            && hasCompletedSnapshotBarriers(state.targets, checkpointState);
        this.states.set(name, state);
        if (state.ready && !wasReady) {
            this.logger.log(`Kafka projection caught up: ${state.stream.topic}`);
            this.notifyIfReady();
        } else if (!state.ready && wasReady) {
            this.logger.warn(`Kafka projection readiness closed: ${state.stream.topic}`);
        }
    }

    private notifyIfReady(): void {
        if (this.isReady()) this.resolveReady();
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
        this.fatalInvariantReason = reason;
        for (const state of this.states.values()) state.ready = false;
        this.logger.error(reason);
        for (const listener of this.fatalInvariantListeners) listener(reason);
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
