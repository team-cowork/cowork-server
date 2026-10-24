import {
    BadRequestException,
    Injectable,
    Logger,
    OnModuleDestroy,
} from '@nestjs/common';
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
import {
    ProjectionDatasetRepository,
    ProjectionDatasetState,
    ProjectionStreamName,
} from './projection-dataset.repository';
import {ProjectionDatasetStatus, ProjectionRunMode} from './projection-dataset.schema';
import {ProjectionMetricsService} from './projection-metrics.service';

export type ProjectionStream = {
    name: string;
    topic: string;
    groupId: string;
    expectedSource: string;
    sourceGeneration: string;
};

export type StartupPartitionOffset = {
    low: string;
    high: string;
} & PartitionOffset;

const DEFAULT_SOURCE_GENERATION = process.env.CHAT_PROJECTION_SOURCE_GENERATION ?? '1';
const sourceGeneration = (name: string): string => (
    process.env[`CHAT_PROJECTION_${name}_SOURCE_GENERATION`] ?? DEFAULT_SOURCE_GENERATION
);

export const PROJECTION_STREAMS = {
    channel: {
        name: 'channel',
        topic: 'channel.event.v2',
        groupId: 'cowork-chat-channel-event-v2-projection',
        expectedSource: 'cowork-channel',
        sourceGeneration: sourceGeneration('CHANNEL'),
    },
    project: {
        name: 'project',
        topic: 'project.event.v2',
        groupId: 'cowork-chat-project-event-v2-projection',
        expectedSource: 'cowork-project',
        sourceGeneration: sourceGeneration('PROJECT'),
    },
    dmParticipant: {
        name: 'dmParticipant',
        topic: 'channel.dm-participant.state.v1',
        groupId: 'cowork-chat-dm-participant-v1-projection',
        expectedSource: 'cowork-channel',
        sourceGeneration: sourceGeneration('DM_PARTICIPANT'),
    },
    projectPermission: {
        name: 'projectPermission',
        topic: 'preference.project-access.state.v1',
        groupId: 'cowork-chat-project-permission-v1-projection',
        expectedSource: 'cowork-preference',
        sourceGeneration: sourceGeneration('PROJECT_PERMISSION'),
    },
    projectGithubRepo: {
        name: 'projectGithubRepo',
        topic: 'project.github-repo.event',
        groupId: 'cowork-chat-project-github-repo-event-v1-projection',
        expectedSource: 'cowork-project',
        sourceGeneration: sourceGeneration('PROJECT_GITHUB_REPO'),
    },
    teamMember: {
        name: 'teamMember',
        topic: 'team.member.event',
        groupId: 'cowork-chat-team-member-event-v1-projection',
        expectedSource: 'cowork-team',
        sourceGeneration: sourceGeneration('TEAM_MEMBER'),
    },
    teamRole: {
        name: 'teamRole',
        topic: 'preference.team-role.state.v2',
        groupId: 'cowork-chat-team-role-state-v2-projection',
        expectedSource: 'cowork-preference',
        sourceGeneration: sourceGeneration('TEAM_ROLE'),
    },
    channelRolePolicy: {
        name: 'channelRolePolicy',
        topic: 'preference.channel-role-policy.state.v2',
        groupId: 'cowork-chat-channel-role-policy-state-v2-projection',
        expectedSource: 'cowork-preference',
        sourceGeneration: sourceGeneration('CHANNEL_ROLE_POLICY'),
    },
    textChannelSetting: {
        name: 'textChannelSetting',
        topic: 'preference.text-channel-setting.state.v1',
        groupId: 'cowork-chat-text-channel-setting-state-v1-projection',
        expectedSource: 'cowork-preference',
        sourceGeneration: sourceGeneration('TEXT_CHANNEL_SETTING'),
    },
    userProfile: {
        name: 'userProfile',
        topic: 'user.profile.event.v2',
        groupId: 'cowork-chat-user-profile-event-v2-projection',
        expectedSource: 'cowork-user',
        sourceGeneration: sourceGeneration('USER_PROFILE'),
    },
} as const satisfies Record<ProjectionStreamName, ProjectionStream>;

export type ProjectionName = keyof typeof PROJECTION_STREAMS;

type PendingProjectionAssignment = {
    revision: number;
    memberId: string;
    assignedPartitions: number[];
    afterHeartbeatSequence: number;
    groupGenerationId?: number;
    claimInFlight: boolean;
};

type ProjectionCatchupState = {
    stream: ProjectionStream;
    consumer: Consumer;
    targets: StartupPartitionOffset[];
    checkpoints: Map<number, string>;
    checkpointLeases: Map<number, ProjectionAssignmentLease>;
    snapshotBarriers: Map<number, string>;
    invalidRecordOffsets: Map<number, string>;
    snapshotOccurredAt: Map<number, Date>;
    localAssignmentLeases: Map<number, ProjectionAssignmentLease>;
    pendingAssignment?: PendingProjectionAssignment;
    heartbeatSequence: number;
    renewalInFlight: boolean;
    leaseRenewalPaused: boolean;
    assignmentObserved: boolean;
    assignmentRevision: number;
    assignmentTask: Promise<void>;
    dataset: ProjectionDatasetState;
    startOffsets: Map<number, string>;
    activatedDatasetGeneration?: string;
    catchupStartedAt: number;
    lastDocumentCountCheckAt?: number;
    ready: boolean;
    checkPromise?: Promise<void>;
    pollTimer?: NodeJS.Timeout;
};

export type ProjectionPartitionStatus = {
    partition: number;
    startOffset?: string;
    currentOffset?: string;
    targetOffset: string;
    lowOffset: string;
    lag?: string;
};

export type ProjectionStreamStatus = {
    ready: boolean;
    mode: ProjectionRunMode;
    status: ProjectionDatasetStatus;
    datasetGeneration: string;
    sourceGeneration: string;
    reason?: string;
    partitions: ProjectionPartitionStatus[];
};

type ProjectionAdminConnection = {
    kafka: Kafka;
    admin?: Admin;
    connectPromise?: Promise<Admin>;
};

const POLL_INTERVAL_MS = 1000;
/**
 매 poll(1초)마다 document count를 정확히 세면 대용량 컬렉션에 상시 부하를 준다. 외부 개입으로 인한
 유실은 초 단위로 빠르게 잡을 이유가 없으므로, 훨씬 느슨한 주기로만 추정치를 다시 확인한다.
 */
const DOCUMENT_COUNT_CHECK_INTERVAL_MS = Number(
    process.env.CHAT_PROJECTION_DOCUMENT_COUNT_CHECK_INTERVAL_MS ?? 60_000,
);
const MIN_DOCUMENT_COUNT_DROP = Number(process.env.CHAT_PROJECTION_PARTIAL_LOSS_MIN_DOCUMENTS ?? 20);
const MIN_DOCUMENT_COUNT_DROP_RATIO = Number(process.env.CHAT_PROJECTION_PARTIAL_LOSS_MIN_RATIO ?? 0.2);
/**
 Kafkajs RequestQueue는 broker throttling 등으로 pending queue에 남은 요청에는 requestTimeout을
 적용하지 않아(inflight 요청만 sweep한다) 한 번 막힌 admin 커넥션이 영원히 응답하지 않을 수 있다.
 상한을 넘긴 요청은 실패시키고 커넥션을 폐기해 다음 호출이 새 커넥션을 쓰게 한다.
 */
const ADMIN_REQUEST_TIMEOUT_MS = 15_000;
/**
 막힌 커넥션의 disconnect도 응답하지 않을 수 있으므로 종료 대기에 상한을 둔다. 상한이 없으면
 graceful shutdown이 termination grace period 만료(SIGKILL)까지 밀린다.
 */
const ADMIN_DISCONNECT_TIMEOUT_MS = 2000;

/**
상한을 넘긴 작업을 나머지 실패와 구분한다.
*/
class ProjectionOperationTimeoutError extends Error {}

/**
 응답하지 않을 수 있는 작업에 상한을 둔다. 상한을 넘겨도 원본 작업은 계속 pending일 수 있으므로,
 자원을 폐기할 책임은 호출자에게 있다.
 */
async function withTimeout<T>(work: Promise<T>, timeoutMs: number, message: string): Promise<T> {
    let timer: NodeJS.Timeout | undefined;
    const timeout = new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => {
            // 상한을 넘긴 뒤 원본이 늦게 실패하면 unhandled rejection이 되므로 미리 삼킨다.
            void work.catch(() => undefined);
            reject(new ProjectionOperationTimeoutError(message));
        }, timeoutMs);
        timer.unref();
    });
    return Promise.race([work, timeout]).finally(() => {
        if (timer) {
            clearTimeout(timer);
        }
    });
}

function isSameLease(
    checkpoint: ProjectionCheckpointOffset | undefined,
    lease: ProjectionAssignmentLease,
): boolean {
    return checkpoint?.assignmentEpoch === lease.assignmentEpoch
        && checkpoint.assignmentMemberId === lease.memberId
        && checkpoint.assignmentGenerationId === lease.groupGenerationId;
}

/**
Kafka offset은 JavaScript 안전 정수 범위를 넘을 수 있으므로 항상 BigInt로 비교한다.
*/
function compareOffsets(left: string, right: string): number {
    const leftOffset = BigInt(left);
    const rightOffset = BigInt(right);
    if (leftOffset === rightOffset) {
        return 0;
    }

    return leftOffset > rightOffset ? 1 : -1;
}

/**
저장된 checkpoint가 Kafka에 남아 있는 replay 가능 범위 안인지 판정한다.
*/
export function isCheckpointWithinRetainedRange(
    checkpoint: string,
    target: StartupPartitionOffset,
): boolean {
    return compareOffsets(checkpoint, target.low) >= 0
        && compareOffsets(checkpoint, target.high) <= 0;
}

/**
 정상적인 삭제 이벤트 처리로 생기는 document count 변동과, DB 복원·수동 삭제 같은 외부 개입으로
 생기는 급격한 유실을 구분한다. 절대값과 비율을 모두 넘는 감소만 의심스럽다고 판정해, 작은
 컬렉션의 정상적인 등락이나 실제로는 적은 수의 정상 삭제를 오탐하지 않는다.
 */
export function isSuspiciousDocumentCountDrop(
    baseline: number,
    observed: number,
    options: {minAbsoluteDrop?: number; minRatio?: number} = {},
): boolean {
    if (baseline <= 0 || observed >= baseline) {
        return false;
    }

    const minAbsoluteDrop = options.minAbsoluteDrop ?? MIN_DOCUMENT_COUNT_DROP;
    const minRatio = options.minRatio ?? MIN_DOCUMENT_COUNT_DROP_RATIO;
    const drop = baseline - observed;
    return drop >= minAbsoluteDrop && drop / baseline >= minRatio;
}

/**
시작 시 캡처한 파티션별 high-watermark까지 shared checkpoint가 도달했는지 판정한다.
*/
export function hasReachedStartupOffsets(
    targets: StartupPartitionOffset[],
    checkpoints: PartitionOffset[],
): boolean {
    const checkpointByPartition = new Map(
        checkpoints.map(({partition, offset}) => [partition, offset]),
    );

    return targets.every(target => {
        const checkpoint = checkpointByPartition.get(target.partition);
        if (checkpoint === undefined) {
            return false;
        }

        try {
            return compareOffsets(checkpoint, target.high) >= 0;
        } catch {
            return false;
        }
    });
}

/**
모든 partition에서 full snapshot marker까지 checkpoint가 전진했는지 판정한다.
*/
export function hasCompletedSnapshotBarriers(
    targets: StartupPartitionOffset[],
    checkpoints: ProjectionCheckpointOffset[],
): boolean {
    const checkpointByPartition = new Map(checkpoints.map(checkpoint => [checkpoint.partition, checkpoint]));
    return targets.every(({partition}) => {
        const checkpoint = checkpointByPartition.get(partition);
        if (checkpoint?.assignmentEpoch === undefined
            || checkpoint.assignmentMemberId === undefined
            || checkpoint.assignmentGenerationId === undefined
            || checkpoint.snapshotCompletedOffset === undefined
            || checkpoint.invalidRecordOffset !== undefined) {
            return false;
        }

        try {
            const target = targets.find(candidate => candidate.partition === partition);
            return target !== undefined
                && compareOffsets(checkpoint.snapshotCompletedOffset, target.low) >= 0
                && compareOffsets(checkpoint.offset, checkpoint.snapshotCompletedOffset) > 0;
        } catch {
            return false;
        }
    });
}

/**
 Kafka projection consumer들의 shared Mongo replay barrier.

 consumer가 실행되기 직전에 토픽의 high-watermark와 Mongo checkpoint를 캡처하고, group
 assignment 때 broker group offset 대신 checkpoint(없으면 earliest)로 seek한다. projection
 반영 뒤 checkpoint를 저장하므로 Mongo가 초기화된 환경에서도 기존 broker group commit이
 이벤트 replay를 건너뛰지 못한다.
 */
@Injectable()
export class ProjectionReadinessService implements OnModuleDestroy {
    private readonly logger = new Logger(ProjectionReadinessService.name);
    private readonly states = new Map<ProjectionName, ProjectionCatchupState>();
    private readonly registrations = new Map<ProjectionName, Promise<void>>();
    private readonly admins = new Map<ProjectionName, ProjectionAdminConnection>();
    private stopped = false;
    private fatalInvariantReason?: string;
    private readonly fatalInvariantListeners = new Set<(reason: string) => void>();
    private resolveReady!: () => void;
    private readyPromise: Promise<void>;

    constructor(
        private readonly checkpoints: ProjectionCheckpointRepository,
        private readonly datasets: ProjectionDatasetRepository,
        private readonly metrics: ProjectionMetricsService,
    ) {
        this.readyPromise = this.createReadyPromise();
    }

    async registerProjection(kafka: Kafka, consumer: Consumer, stream: ProjectionStream): Promise<void> {
        const name = this.validateStream(stream);
        const existing = this.registrations.get(name);
        if (existing) {
            return existing;
        }

        this.admins.set(name, {kafka});
        const registration = this.captureStartupState(consumer, name, stream).catch((error: unknown) => {
            this.registrations.delete(name);
            this.resetAdmin(name);
            throw error;
        });
        this.registrations.set(name, registration);
        return registration;
    }

    /**
    Projection 처리 성공 뒤 next offset을 shared Mongo에 기록한다.
    */
    async processMessage(
        stream: ProjectionStream,
        partition: number,
        offset: string,
        applyProjection: () => Promise<void | {snapshotBarrier?: SnapshotBarrierReceipt}>,
    ): Promise<void> {
        const name = this.validateStream(stream);
        const state = this.states.get(name);
        if (!state) {
            throw new Error(`Kafka projection stream is not registered: ${stream.name}`);
        }

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
        this.metrics.recordReplay(stream.name, state.dataset.mode);
        if (result?.snapshotBarrier) {
            state.snapshotBarriers.set(partition, result.snapshotBarrier.offset);
            state.snapshotOccurredAt.set(partition, result.snapshotBarrier.occurredAt);
        }

        // Assignment에서 readiness를 닫은 뒤에는 fresh broker high-watermark를
        // 다시 읽는 poll만 ready를 열 수 있다.
        this.states.set(name, state);
        this.schedulePoll(name);
    }

    /**
    Malformed record 원문을 먼저 영속화한다. 실패하면 processMessage도 실패해 offset이 유지된다.
    */
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
        if (!state) {
            throw new Error(`Kafka projection stream is not registered: ${stream.name}`);
        }

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
        return Object.keys(PROJECTION_STREAMS).every(name => this.states.get(name as ProjectionName)?.ready === true);
    }

    areReady(streams: readonly ProjectionName[]): boolean {
        return streams.every(name => this.states.get(name)?.ready === true);
    }

    async whenReady(): Promise<void> {
        return this.isReady() ? Promise.resolve() : this.readyPromise;
    }

    /**
     이 stream이 live mutation을 전달하는 중인지 판정한다.
     bootstrap catch-up과 rebuild replay 중에는 projection이 아직 권위가 없으므로,
     과거 레코드를 다시 적용하면서 소켓 이벤트나 접근 취소를 내보내면 안 된다.
     */
    isStreamLive(name: ProjectionName): boolean {
        return this.states.get(name)?.ready === true;
    }

    onFatalInvariantViolation(listener: (reason: string) => void): () => void {
        this.fatalInvariantListeners.add(listener);
        if (this.fatalInvariantReason !== undefined) {
            listener(this.fatalInvariantReason);
        }

        return () => {
            this.fatalInvariantListeners.delete(listener);
        };
    }

    getStatus(): Record<ProjectionName, boolean> {
        return Object.fromEntries(
            Object.keys(PROJECTION_STREAMS).map(name => [name, this.states.get(name as ProjectionName)?.ready === true]),
        ) as Record<ProjectionName, boolean>;
    }

    getDetailedStatus(): Partial<Record<ProjectionName, ProjectionStreamStatus>> {
        return Object.fromEntries([...this.states].map(([name, state]) => [name, {
            ready: state.ready,
            mode: state.dataset.mode,
            status: state.dataset.status,
            datasetGeneration: state.dataset.datasetGeneration,
            sourceGeneration: state.dataset.sourceGeneration,
            ...(state.dataset.reason !== undefined && {reason: state.dataset.reason}),
            partitions: state.targets.map(target => {
                const currentOffset = state.checkpoints.get(target.partition);
                return {
                    partition: target.partition,
                    startOffset: state.startOffsets.get(target.partition),
                    currentOffset,
                    targetOffset: target.high,
                    lowOffset: target.low,
                    ...(currentOffset !== undefined && {
                        lag: (BigInt(target.high) > BigInt(currentOffset)
                            ? BigInt(target.high) - BigInt(currentOffset)
                            : 0n).toString(),
                    }),
                };
            }),
        }]));
    }

    async requestRebuild(streamName: string, reason: string): Promise<ProjectionDatasetState> {
        if (!Object.hasOwn(PROJECTION_STREAMS, streamName)) {
            throw new BadRequestException(`Unknown projection stream: ${streamName}`);
        }

        const name = streamName as ProjectionName;
        const state = this.states.get(name);
        if (!state) {
            throw new BadRequestException(`Projection stream is not registered: ${streamName}`);
        }

        const offsets = await this.withAdmin(
            name,
            async admin => admin.fetchTopicOffsets(state.stream.topic),
            `fetchTopicOffsets(${state.stream.topic})`,
        );
        const dataset = await this.datasets.requestRebuild(
            state.stream,
            reason,
            offsets.map(({partition, low}) => ({partition, offset: low})),
            offsets.map(({partition, high}) => ({partition, offset: high})),
        );
        state.dataset = dataset;
        state.ready = false;
        state.catchupStartedAt = Date.now();
        this.metrics.recordRebuild(name, 'requested');
        this.states.set(name, state);
        await this.driveRebuild(name, state);
        this.schedulePoll(name);
        return state.dataset;
    }

    /**
     Shared checkpoint를 즉시 다시 읽어 다중 replica의 진행 상황을 반영한다.
     stream마다 독립적으로 검사하고 각자 다음 poll을 예약하므로, 한 stream이 admin 요청 상한을
     다 써도 다른 stream의 poll 주기가 그만큼 밀리지 않는다.
     */
    async checkCatchup(): Promise<void> {
        if (this.stopped) {
            return;
        }

        await Promise.all([...this.states.keys()].map(async name => this.checkStream(name)));
    }

    /**
    한 stream의 검사만 중복 없이 실행하고, 끝나면 그 stream의 다음 poll을 예약한다.
    */
    private async checkStream(name: ProjectionName): Promise<void> {
        if (this.stopped) {
            return;
        }

        const state = this.states.get(name);
        if (!state) {
            return;
        }

        if (state.checkPromise) {
            return state.checkPromise;
        }

        state.checkPromise = this.doCheckStream(name, state).finally(() => {
            state.checkPromise = undefined;
            this.schedulePoll(name);
        });
        return state.checkPromise;
    }

    async onModuleDestroy(): Promise<void> {
        this.stopped = true;
        for (const state of this.states.values()) {
            if (state.pollTimer) {
                clearTimeout(state.pollTimer);
            }

            state.pollTimer = undefined;
        }

        await Promise.all([...this.states.values()].map(async state => {
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

        // Pending connectPromise는 connect 재시도 백오프를 그대로 기다리게 되므로 await하지 않는다.
        // disconnect도 응답하지 않을 수 있어(resetAdmin과 같은 판단) 상한을 두고 기다린다.
        await Promise.all([...this.admins.values()].map(async connection => {
            const {admin} = connection;
            connection.admin = undefined;
            connection.connectPromise = undefined;
            if (!admin) {
                return;
            }

            await withTimeout(
                admin.disconnect(),
                ADMIN_DISCONNECT_TIMEOUT_MS,
                `Kafka projection admin disconnect timed out after ${ADMIN_DISCONNECT_TIMEOUT_MS}ms`,
            ).catch(() => undefined);
        }));
        this.admins.clear();
    }

    private async resolveStartupDataset(
        name: ProjectionName,
        stream: ProjectionStream,
        targets: StartupPartitionOffset[],
    ): Promise<ProjectionDatasetState> {
        const [dataset, checkpoints, documentCount] = await Promise.all([
            this.datasets.find(name),
            this.checkpoints.findAll(stream.groupId, stream.topic),
            this.datasets.countProjection(name),
        ]);
        if (!dataset) {
            if (checkpoints.length > 0 || documentCount > 0) {
                return this.datasets.markRebuildRequired(
                    stream,
                    'Projection metadata is missing while checkpoint/data exists '
                    + `(checkpoints=${checkpoints.length}, documents=${documentCount})`,
                );
            }

            return this.datasets.createBootstrap(
                stream,
                targets.map(({partition, low}) => ({partition, offset: low})),
                targets.map(({partition, high}) => ({partition, offset: high})),
            );
        }

        if (dataset.groupId !== stream.groupId || dataset.topic !== stream.topic) {
            return this.datasets.markRebuildRequired(stream, 'Projection dataset stream identity changed');
        }

        if (dataset.sourceGeneration !== stream.sourceGeneration) {
            return this.datasets.markRebuildRequired(
                stream,
                `Projection source generation mismatch: stored=${dataset.sourceGeneration} `
                + `configured=${stream.sourceGeneration}`,
            );
        }

        if (['REBUILD_REQUIRED', 'REBUILD_REQUESTED', 'RESETTING'].includes(dataset.status)) {
            return dataset;
        }

        if (dataset.status === 'ACTIVE') {
            const baseline = dataset.lastObservedDocumentCount ?? dataset.activationDocumentCount;
            if (isSuspiciousDocumentCountDrop(baseline, documentCount)) {
                return this.datasets.markRebuildRequired(
                    stream,
                    'Projection collection lost documents since it was last observed '
                    + `(baseline=${baseline}, observed=${documentCount})`,
                );
            }
        }

        const targetByPartition = new Map(targets.map(target => [target.partition, target]));
        for (const checkpoint of checkpoints) {
            const target = targetByPartition.get(checkpoint.partition);
            if (checkpoint.datasetGeneration !== dataset.datasetGeneration
                || checkpoint.sourceGeneration !== dataset.sourceGeneration) {
                return this.datasets.markRebuildRequired(
                    stream,
                    `Projection checkpoint generation mismatch: ${stream.topic}[${checkpoint.partition}]`,
                );
            }

            if (!target || !isCheckpointWithinRetainedRange(checkpoint.offset, target)) {
                return this.datasets.markRebuildRequired(
                    stream,
                    `Projection checkpoint is outside retained log: ${stream.topic}[${checkpoint.partition}] `
                    + `checkpoint=${checkpoint.offset} retained=${target?.low ?? 'missing'}-${target?.high ?? 'missing'}`,
                );
            }
        }

        if (dataset.status === 'ACTIVE') {
            const partitions = checkpoints.map(({partition}) => partition).toSorted((a, b) => a - b);
            const expected = targets.map(({partition}) => partition).toSorted((a, b) => a - b);
            if (partitions.join(',') !== expected.join(',')) {
                return this.datasets.markRebuildRequired(
                    stream,
                    `Projection checkpoint partition set is incomplete: stored=${partitions.join(',')} `
                    + `broker=${expected.join(',')}`,
                );
            }

            // Invalid-record latch는 rebuild 대상이 아니다. readiness만 닫아 둔 채 소비를 계속하면
            // 서로 다른 두 full snapshot이 latch를 해제한다.
            if (checkpoints.some(checkpoint => checkpoint.snapshotCompletedOffset === undefined)) {
                return this.datasets.markRebuildRequired(
                    stream,
                    'Active projection dataset has no valid snapshot barrier',
                );
            }
        }

        return dataset;
    }

    private async captureStartupState(
        consumer: Consumer,
        name: ProjectionName,
        stream: ProjectionStream,
    ): Promise<void> {
        const targets = await this.withAdmin(
            name,
            async admin => admin.fetchTopicOffsets(stream.topic),
            `fetchTopicOffsets(${stream.topic})`,
        );
        if (targets.length === 0) {
            throw new Error(`Kafka projection topic has no partitions: ${stream.topic}`);
        }

        const dataset = await this.resolveStartupDataset(name, stream, targets);
        const state: ProjectionCatchupState = {
            stream,
            consumer,
            targets,
            checkpoints: new Map(),
            checkpointLeases: new Map(),
            snapshotBarriers: new Map(),
            invalidRecordOffsets: new Map(),
            snapshotOccurredAt: new Map(),
            localAssignmentLeases: new Map(),
            heartbeatSequence: 0,
            renewalInFlight: false,
            leaseRenewalPaused: false,
            assignmentObserved: false,
            assignmentRevision: 0,
            assignmentTask: Promise.resolve(),
            dataset,
            startOffsets: new Map(),
            catchupStartedAt: Date.now(),
            ready: false,
        };
        this.states.set(name, state);
        this.attachAssignmentFence(consumer, name, state);
        this.logger.log(
            `Projection startup offsets captured: ${stream.topic} mode=${dataset.mode} status=${dataset.status} `
            + `[${targets.map(({partition, low, high}) => `${partition}:${low}-${high}`).join(', ')}]`,
        );
        this.schedulePoll(name);
    }

    private attachAssignmentFence(
        consumer: Consumer,
        name: ProjectionName,
        state: ProjectionCatchupState,
    ): void {
        consumer.on(consumer.events.GROUP_JOIN, (event: ConsumerGroupJoinEvent) => {
            if (this.stopped || event.payload.groupId !== state.stream.groupId) {
                return;
            }

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
                state.snapshotOccurredAt.delete(partition);
                state.invalidRecordOffsets.delete(partition);
            }

            this.closeStreamReadiness(name, state);

            try {
                if (assignedPartitions.length > 0) {
                    consumer.pause([{topic: state.stream.topic, partitions: assignedPartitions}]);
                }
            } catch (error) {
                this.markFatalInvariantViolation(
                    `Kafka projection assignment pause failed: ${state.stream.topic}: `
                    + (error instanceof Error ? error.message : String(error)),
                );
                return;
            }

            this.enqueueAssignmentTask(state, async () => this.releaseAssignments(state, previouslyOwned), error => {
                this.closeStreamReadiness(name, state);
                this.logger.warn(
                    `Kafka projection previous lease release failed [topic=${state.stream.topic}]: `
                    + (error instanceof Error ? error.message : String(error)),
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
        if (this.stopped || event.payload.groupId !== state.stream.groupId) {
            return;
        }

        state.heartbeatSequence += 1;
        const {heartbeatSequence} = state;
        const pending = state.pendingAssignment;
        if (pending) {
            if (heartbeatSequence <= pending.afterHeartbeatSequence
                || event.payload.memberId !== pending.memberId || (pending.groupGenerationId !== undefined
                    && pending.groupGenerationId !== event.payload.groupGenerationId)) {
                return;
            }

            pending.groupGenerationId = event.payload.groupGenerationId;
            if (pending.claimInFlight) {
                return;
            }

            pending.claimInFlight = true;
            this.enqueueAssignmentTask(state, async () => {
                try {
                    await this.claimAssignedPartitions(state, pending);
                } finally {
                    if (state.pendingAssignment === pending) {
                        pending.claimInFlight = false;
                    }
                }
            }, error => {
                this.closeStreamReadiness(name, state);
                if (state.pendingAssignment === pending) {
                    pending.claimInFlight = false;
                }

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
        if (owned.length === 0 || state.renewalInFlight) {
            return;
        }

        const currentLease = owned[0][1];
        if (event.payload.memberId !== currentLease.memberId) {
            return;
        }

        if (event.payload.groupGenerationId !== currentLease.groupGenerationId) {
            this.revokeAssignments(name, state, 'heartbeat generation change');
            return;
        }

        state.renewalInFlight = true;
        const revision = state.assignmentRevision;
        this.enqueueAssignmentTask(state, async () => {
            try {
                await Promise.all(owned.map(async ([partition, lease]) => this.checkpoints.renewAssignment(
                    state.stream.groupId,
                    state.stream.topic,
                    partition,
                    lease,
                )));
                if (revision !== state.assignmentRevision
                    || owned.some(([partition, lease]) => state.localAssignmentLeases.get(partition) !== lease)) {
                    return;
                }

                if (state.leaseRenewalPaused) {
                    state.consumer.resume([{
                        topic: state.stream.topic,
                        partitions: owned.map(([partition]) => partition),
                    }]);
                    state.leaseRenewalPaused = false;
                }

                this.schedulePoll(name);
            } finally {
                state.renewalInFlight = false;
            }
        }, error => {
            state.renewalInFlight = false;
            this.closeStreamReadiness(name, state);
            const partitions = [...state.localAssignmentLeases.keys()];
            try {
                if (partitions.length > 0) {
                    state.consumer.pause([{topic: state.stream.topic, partitions}]);
                    state.leaseRenewalPaused = true;
                }
            } catch (pauseError) {
                this.markFatalInvariantViolation(
                    `Kafka projection lease-loss pause failed: ${state.stream.topic}: `
                    + (pauseError instanceof Error ? pauseError.message : String(pauseError)),
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
            || pending.groupGenerationId === undefined) {
            return;
        }

        const dataset = await this.datasets.find(state.stream.name as ProjectionStreamName);
        if (!dataset) {
            await this.markStreamUnrecoverable(
                state.stream.name as ProjectionName,
                state,
                'Projection dataset metadata disappeared before assignment claim',
            );
            return;
        }

        state.dataset = dataset;
        if (dataset.status === 'REBUILD_REQUESTED' || dataset.status === 'RESETTING') {
            await this.driveRebuild(state.stream.name as ProjectionName, state);
            return;
        }

        if (!['ACTIVE', 'INITIALIZING', 'REBUILDING'].includes(dataset.status)) {
            return;
        }

        const currentOffsets = await this.withAdmin(
            state.stream.name as ProjectionName,
            async admin => admin.fetchTopicOffsets(state.stream.topic),
            `fetchTopicOffsets(${state.stream.topic})`,
        );
        if (state.pendingAssignment !== pending || pending.revision !== state.assignmentRevision) {
            return;
        }

        const startupPartitions = state.targets.map(({partition}) => partition).toSorted((a, b) => a - b);
        const currentPartitions = currentOffsets.map(({partition}) => partition).toSorted((a, b) => a - b);
        if (startupPartitions.join(',') !== currentPartitions.join(',')) {
            throw new Error(
                `partition topology changed: startup=${startupPartitions.join(',')} `
                + `current=${currentPartitions.join(',')}`,
            );
        }

        const currentByPartition = new Map(currentOffsets.map(offset => [offset.partition, offset]));
        const claimed: Array<{
            partition: number;
            target: StartupPartitionOffset;
            lease: ProjectionAssignmentLease;
            checkpoint: ProjectionCheckpointOffset;
        }> = [];
        try {
            /* eslint-disable no-await-in-loop -- sequential lease claim depends on the previous partition's result */
            for (const partition of pending.assignedPartitions) {
                if (state.pendingAssignment !== pending || pending.revision !== state.assignmentRevision) {
                    await this.releaseAssignments(
                        state,
                        claimed.map(({partition: claimedPartition, lease}) => [claimedPartition, lease]),
                    );
                    return;
                }

                const target = currentByPartition.get(partition);
                if (!target) {
                    throw new Error(`assigned partition is missing from broker metadata: ${partition}`);
                }

                const claim = await this.checkpoints.claimForAssignment(
                    state.stream.groupId,
                    state.stream.topic,
                    partition,
                    target.low,
                    dataset.datasetGeneration,
                    dataset.sourceGeneration,
                    pending.memberId,
                    pending.groupGenerationId,
                );
                if (!claim) {
                    await this.releaseAssignments(
                        state,
                        claimed.map(({partition: claimedPartition, lease: claimedLease}) => (
                            [claimedPartition, claimedLease]
                        )),
                    );
                    this.logger.warn(
                        'Kafka projection assignment lease is still active; retrying after heartbeat: '
                        + `${state.stream.topic}[${partition}]`,
                    );
                    return;
                }

                if (!isCheckpointWithinRetainedRange(claim.checkpoint.offset, target)) {
                    await this.checkpoints.releaseAssignment(
                        state.stream.groupId,
                        state.stream.topic,
                        partition,
                        claim.lease,
                    );
                    await this.markStreamUnrecoverable(
                        state.stream.name as ProjectionName,
                        state,
                        `Checkpoint is outside retained log: ${state.stream.topic}[${partition}] `
                        + `checkpoint=${claim.checkpoint.offset} retained=${target.low}-${target.high}`,
                    );
                    return;
                }

                claimed.push({
                    partition, target, lease: claim.lease, checkpoint: claim.checkpoint,
                });
            }
            /* eslint-enable no-await-in-loop -- sequential lease claim depends on the previous partition's result */
        } catch (error) {
            await this.releaseAssignments(
                state,
                claimed.map(({partition, lease}) => [partition, lease]),
            ).catch(() => undefined);
            throw error;
        }

        if (state.pendingAssignment !== pending || pending.revision !== state.assignmentRevision) {
            await this.releaseAssignments(
                state,
                claimed.map(({partition, lease}) => [partition, lease]),
            );
            return;
        }

        state.targets = currentOffsets;
        state.assignmentObserved = true;
        state.pendingAssignment = undefined;
        try {
            for (const {partition, lease, checkpoint} of claimed) {
                state.localAssignmentLeases.set(partition, lease);
                state.checkpoints.set(partition, checkpoint.offset);
                state.startOffsets.set(partition, checkpoint.offset);
                state.checkpointLeases.set(partition, lease);
                if (checkpoint.snapshotCompletedOffset === undefined) {
                    state.snapshotBarriers.delete(partition);
                } else {
                    state.snapshotBarriers.set(partition, checkpoint.snapshotCompletedOffset);
                }

                if (checkpoint.snapshotOccurredAt) {
                    state.snapshotOccurredAt.set(partition, checkpoint.snapshotOccurredAt);
                } else {
                    state.snapshotOccurredAt.delete(partition);
                }

                if (checkpoint.invalidRecordOffset === undefined) {
                    state.invalidRecordOffsets.delete(partition);
                } else {
                    state.invalidRecordOffsets.set(partition, checkpoint.invalidRecordOffset);
                }

                state.consumer.seek({topic: state.stream.topic, partition, offset: checkpoint.offset});
                this.logger.log(
                    `Projection assignment fenced and resumed: ${state.stream.topic}[${partition}] `
                    + `mode=${dataset.mode} offset=${checkpoint.offset}`,
                );
            }

            if (pending.assignedPartitions.length > 0) {
                state.consumer.resume([{topic: state.stream.topic, partitions: pending.assignedPartitions}]);
            }
        } catch (error) {
            state.assignmentObserved = false;
            for (const {partition} of claimed) {
                state.localAssignmentLeases.delete(partition);
                state.checkpoints.delete(partition);
                state.checkpointLeases.delete(partition);
                state.snapshotBarriers.delete(partition);
                state.snapshotOccurredAt.delete(partition);
                state.invalidRecordOffsets.delete(partition);
            }

            await this.releaseAssignments(
                state,
                claimed.map(({partition, lease}) => [partition, lease]),
            ).catch(() => undefined);
            throw new Error(
                `assignment activation failed: ${error instanceof Error ? error.message : String(error)}`,
                {cause: error},
            );
        }

        this.schedulePoll(state.stream.name as ProjectionName);
    }

    private revokeAssignments(
        name: ProjectionName,
        state: ProjectionCatchupState,
        reason: string,
    ): void {
        if (this.stopped) {
            return;
        }

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
            state.snapshotOccurredAt.delete(partition);
            state.invalidRecordOffsets.delete(partition);
        }

        this.closeStreamReadiness(name, state);
        this.enqueueAssignmentTask(state, async () => this.releaseAssignments(state, owned), error => {
            this.logger.warn(
                `Kafka projection lease release failed [topic=${state.stream.topic}, reason=${reason}]: `
                + (error instanceof Error ? error.message : String(error)),
            );
        });
    }

    private async releaseAssignments(
        state: ProjectionCatchupState,
        assignments: Array<[number, ProjectionAssignmentLease]>,
    ): Promise<void> {
        await Promise.all(assignments.map(async ([partition, lease]) => this.checkpoints.releaseAssignment(
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

    /**
     Projection마다 전용 admin 커넥션을 쓴다. 하나를 공유하면 backlog가 큰 stream이
     커넥션을 점유하는 동안 다른 stream의 등록/poll이 같은 커넥션 뒤에 head-of-line
     blocking으로 묶인다.
     */
    private async getAdmin(name: ProjectionName): Promise<Admin> {
        const connection = this.admins.get(name);
        if (!connection) {
            throw new Error(`Kafka projection admin is not registered: ${name}`);
        }

        if (!connection.connectPromise) {
            const admin = connection.kafka.admin();
            // Connect가 응답하지 않는 동안에도 커넥션을 폐기할 수 있도록 handle을 먼저 등록한다.
            connection.admin = admin;
            // 실패한 연결을 memoize하면 이후 모든 호출이 같은 rejection을 그대로 재사용한다.
            const connectPromise: Promise<Admin> = admin.connect()
                .then(() => admin)
                .catch((error: unknown) => {
                    // 이미 폐기되고 새 커넥션이 등록됐다면 그것을 덮어쓰지 않는다.
                    if (connection.admin === admin) {
                        connection.admin = undefined;
                    }

                    if (connection.connectPromise === connectPromise) {
                        connection.connectPromise = undefined;
                    }

                    throw error;
                });
            connection.connectPromise = connectPromise;
        }

        return connection.connectPromise;
    }

    /**
     Admin 요청에 상한을 두고, 상한을 넘기면 해당 커넥션을 폐기해 다음 호출이 재연결하게 한다.
     connect도 같은 상한 안에 둔다. 응답하지 않는 connect를 상한 밖에 두면 memoize된
     connectPromise가 남아 같은 stream의 이후 호출이 전부 그 뒤에 묶이고, 폐기 경로도 돌지 않아
     복구할 수 없다.
     */
    private async withAdmin<T>(
        name: ProjectionName,
        run: (admin: Admin) => Promise<T>,
        operation: string,
    ): Promise<T> {
        const connectPromise = this.getAdmin(name);
        try {
            return await withTimeout(
                connectPromise.then(run),
                ADMIN_REQUEST_TIMEOUT_MS,
                `Kafka projection admin request timed out after ${ADMIN_REQUEST_TIMEOUT_MS}ms `
                + `[stream=${name}, operation=${operation}]`,
            );
        } catch (error) {
            if (error instanceof ProjectionOperationTimeoutError) {
                this.resetAdmin(name, connectPromise);
            }

            throw error;
        }
    }

    /**
    막힌 admin 커넥션을 등록에서 떼어낸다. disconnect 자체도 응답하지 않을 수 있어 대기하지 않는다.
    */
    private resetAdmin(name: ProjectionName, staleConnectPromise?: Promise<Admin>): void {
        const connection = this.admins.get(name);
        if (!connection || (staleConnectPromise && connection.connectPromise !== staleConnectPromise)) {
            return;
        }

        const {admin} = connection;
        connection.admin = undefined;
        connection.connectPromise = undefined;
        if (!admin) {
            return;
        }

        this.logger.warn(`Kafka projection admin connection is being recycled [stream=${name}]`);
        void admin.disconnect().catch(() => undefined);
    }

    /**
    한 stream의 dataset/checkpoint/high-watermark를 다시 읽어 그 stream의 readiness만 갱신한다.
    */
    private async doCheckStream(name: ProjectionName, state: ProjectionCatchupState): Promise<void> {
        try {
            const dataset = await this.datasets.find(name);
            if (!dataset) {
                await this.markStreamUnrecoverable(name, state, 'Projection dataset metadata disappeared');
                return;
            }

            state.dataset = dataset;
            if (['REBUILD_REQUESTED', 'RESETTING', 'REBUILDING'].includes(dataset.status)) {
                await this.driveRebuild(name, state);
                if (state.dataset.status !== 'REBUILDING' && state.dataset.status !== 'ACTIVE') {
                    return;
                }
            }

            if (state.dataset.status === 'REBUILD_REQUIRED') {
                this.closeStreamReadiness(name, state);
                return;
            }

            if (!state.assignmentObserved) {
                this.closeStreamReadiness(name, state);
                return;
            }

            const {assignmentRevision} = state;
            await state.assignmentTask;
            if (assignmentRevision !== state.assignmentRevision) {
                return;
            }

            const storedCheckpoints = await this.checkpoints.find(state.stream.groupId, state.stream.topic);
            if (assignmentRevision !== state.assignmentRevision) {
                return;
            }

            const currentOffsets = await this.withAdmin(
                name,
                async admin => admin.fetchTopicOffsets(state.stream.topic),
                `fetchTopicOffsets(${state.stream.topic})`,
            );
            if (assignmentRevision !== state.assignmentRevision) {
                return;
            }

            const currentByPartition = new Map(currentOffsets.map(offset => [offset.partition, offset]));
            /* eslint-disable no-await-in-loop -- stop at the first unrecoverable checkpoint instead of marking it multiple times */
            for (const checkpoint of storedCheckpoints) {
                const current = currentByPartition.get(checkpoint.partition);
                if (!current || !isCheckpointWithinRetainedRange(checkpoint.offset, current)) {
                    await this.markStreamUnrecoverable(
                        name,
                        state,
                        'Kafka projection checkpoint is outside the retained log: '
                        + `${state.stream.topic}[${checkpoint.partition}] checkpoint=${checkpoint.offset} `
                        + `retained=${current?.low ?? 'missing'}-${current?.high ?? 'missing'}`,
                    );
                    return;
                }

                if (checkpoint.datasetGeneration !== state.dataset.datasetGeneration
                    || checkpoint.sourceGeneration !== state.dataset.sourceGeneration) {
                    await this.markStreamUnrecoverable(
                        name,
                        state,
                        'Kafka projection checkpoint generation mismatch: '
                        + `${state.stream.topic}[${checkpoint.partition}]`,
                    );
                    return;
                }
            }
            /* eslint-enable no-await-in-loop -- stop at the first unrecoverable checkpoint instead of marking it multiple times */

            const storedByPartition = new Map(
                storedCheckpoints.map(checkpoint => [checkpoint.partition, checkpoint]),
            );
            for (const [partition, lease] of state.localAssignmentLeases) {
                if (!isSameLease(storedByPartition.get(partition), lease)) {
                    this.markFatalInvariantViolation(
                        'Kafka projection assignment fence was superseded: '
                        + `${state.stream.topic}[${partition}]`,
                    );
                    return;
                }
            }

            const startupPartitions = state.targets.map(({partition}) => partition).toSorted((a, b) => a - b);
            const currentPartitions = currentOffsets.map(({partition}) => partition).toSorted((a, b) => a - b);
            if (startupPartitions.join(',') !== currentPartitions.join(',')) {
                this.markFatalInvariantViolation(
                    `Kafka projection partition topology changed: ${state.stream.topic} `
                    + `startup=${startupPartitions.join(',')} current=${currentPartitions.join(',')}`,
                );
                return;
            }

            state.targets = currentOffsets;
            state.checkpoints = new Map(storedCheckpoints.map(({partition, offset}) => [partition, offset]));
            state.checkpointLeases = new Map(
                storedCheckpoints.flatMap(({
                    partition,
                    assignmentEpoch,
                    assignmentMemberId,
                    assignmentGenerationId,
                }) => (
                    assignmentEpoch !== undefined
                    && assignmentMemberId !== undefined
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
                storedCheckpoints.flatMap(({partition, snapshotCompletedOffset}) => (
                    snapshotCompletedOffset === undefined ? [] : [[partition, snapshotCompletedOffset] as const]
                )),
            );
            state.snapshotOccurredAt = new Map(
                storedCheckpoints.flatMap(({partition, snapshotOccurredAt}) => (
                    snapshotOccurredAt === undefined ? [] : [[partition, snapshotOccurredAt] as const]
                )),
            );
            state.invalidRecordOffsets = new Map(
                storedCheckpoints.flatMap(({partition, invalidRecordOffset}) => (
                    invalidRecordOffset === undefined ? [] : [[partition, invalidRecordOffset] as const]
                )),
            );
            if (state.dataset.status === 'ACTIVE' && await this.checkDocumentCountContinuity(name, state)) {
                return;
            }

            await this.updateReady(name, state);
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            this.closeStreamReadiness(name, state);
            this.logger.warn(`Kafka projection catch-up check failed [topic=${state.stream.topic}]: ${message}`);
        }
    }

    /**
     DOCUMENT_COUNT_CHECK_INTERVAL_MS마다 한 번씩만 추정치를 다시 확인해, 매초 도는 catch-up
     poll에서 대용량 컬렉션에 상시 count 부하를 주지 않는다. 의심스러운 감소를 찾으면 그 stream을
     rebuild 대상으로 돌리고 true를 반환해, 호출자가 이번 tick의 readiness 판정을 건너뛰게 한다.
     */
    private async checkDocumentCountContinuity(name: ProjectionName, state: ProjectionCatchupState): Promise<boolean> {
        const now = Date.now();
        if (state.lastDocumentCountCheckAt !== undefined
            && now - state.lastDocumentCountCheckAt < DOCUMENT_COUNT_CHECK_INTERVAL_MS) {
            return false;
        }

        state.lastDocumentCountCheckAt = now;
        const baseline = state.dataset.lastObservedDocumentCount ?? state.dataset.activationDocumentCount;
        const observed = await this.datasets.estimateProjectionCount(name);
        if (isSuspiciousDocumentCountDrop(baseline, observed)) {
            await this.markStreamUnrecoverable(
                name,
                state,
                'Projection collection lost documents while active '
                + `(baseline=${baseline}, observed=${observed})`,
            );
            return true;
        }

        await this.datasets.recordObservedDocumentCount(name, state.dataset.datasetGeneration, observed);
        state.dataset = {...state.dataset, lastObservedDocumentCount: observed};
        return false;
    }

    private async updateReady(name: ProjectionName, state: ProjectionCatchupState): Promise<void> {
        const wasGloballyReady = this.isReady();
        const checkpointOffsets = [...state.checkpoints].map(([partition, offset]) => ({partition, offset}));
        const checkpointState = checkpointOffsets.map(checkpoint => ({
            ...checkpoint,
            assignmentEpoch: state.checkpointLeases.get(checkpoint.partition)?.assignmentEpoch,
            assignmentMemberId: state.checkpointLeases.get(checkpoint.partition)?.memberId,
            assignmentGenerationId: state.checkpointLeases.get(checkpoint.partition)?.groupGenerationId,
            snapshotCompletedOffset: state.snapshotBarriers.get(checkpoint.partition),
            invalidRecordOffset: state.invalidRecordOffsets.get(checkpoint.partition),
        }));
        const wasReady = state.ready;
        const isFreshRebuildSnapshot = state.dataset.mode !== 'REBUILD'
            || (state.dataset.rebuildRequestedAt !== undefined
                && state.targets.every(({partition}) => {
                    const occurredAt = state.snapshotOccurredAt.get(partition);
                    return occurredAt !== undefined && occurredAt >= (state.dataset.rebuildRequestedAt!);
                }));
        const isCaughtUp = this.fatalInvariantReason === undefined
            && state.assignmentObserved
            && !state.leaseRenewalPaused
            && hasReachedStartupOffsets(state.targets, checkpointOffsets)
            && hasCompletedSnapshotBarriers(state.targets, checkpointState)
            && isFreshRebuildSnapshot;
        state.ready = isCaughtUp && state.dataset.status === 'ACTIVE';
        if (isCaughtUp && (state.dataset.status === 'INITIALIZING' || state.dataset.status === 'REBUILDING')) {
            await this.activateDataset(name, state);
        }

        this.states.set(name, state);
        if (state.ready && !wasReady) {
            this.logger.log(`Kafka projection caught up: ${state.stream.topic}`);
        } else if (!state.ready && wasReady) {
            this.logger.warn(`Kafka projection readiness closed: ${state.stream.topic}`);
        }

        const isGloballyReady = this.isReady();
        if (isGloballyReady && !wasGloballyReady) {
            this.resolveReady();
        } else if (!isGloballyReady && wasGloballyReady) {
            this.readyPromise = this.createReadyPromise();
        }
    }

    private schedulePoll(name: ProjectionName): void {
        if (this.stopped) {
            return;
        }

        const state = this.states.get(name);
        if (!state || state.pollTimer) {
            return;
        }

        state.pollTimer = setTimeout(() => {
            state.pollTimer = undefined;
            void this.checkStream(name);
        }, POLL_INTERVAL_MS);
        state.pollTimer.unref();
    }

    private markFatalInvariantViolation(reason: string): void {
        if (this.fatalInvariantReason !== undefined) {
            return;
        }

        const wasGloballyReady = this.isReady();
        this.fatalInvariantReason = reason;
        for (const state of this.states.values()) {
            state.ready = false;
        }

        if (wasGloballyReady) {
            this.readyPromise = this.createReadyPromise();
        }

        this.logger.error(reason);
        for (const listener of this.fatalInvariantListeners) {
            listener(reason);
        }
    }

    private async markStreamUnrecoverable(
        name: ProjectionName,
        state: ProjectionCatchupState,
        reason: string,
    ): Promise<void> {
        state.dataset = await this.datasets.markRebuildRequired(state.stream, reason);
        this.closeStreamReadiness(name, state);
        const partitions = [...state.localAssignmentLeases.keys()];
        if (partitions.length > 0) {
            state.consumer.pause([{topic: state.stream.topic, partitions}]);
        }

        this.logger.error(`Projection rebuild required [stream=${name}]: ${reason}`);
    }

    private async driveRebuild(name: ProjectionName, state: ProjectionCatchupState): Promise<void> {
        if (state.dataset.status === 'REBUILD_REQUESTED') {
            const owned = [...state.localAssignmentLeases];
            if (owned.length > 0) {
                state.consumer.pause([{topic: state.stream.topic, partitions: owned.map(([partition]) => partition)}]);
                await Promise.all(owned.map(async ([partition, lease]) => this.checkpoints.acknowledgeRebuildPause(
                    state.stream.groupId,
                    state.stream.topic,
                    partition,
                    lease,
                    state.dataset.datasetGeneration,
                )));
            }

            this.closeStreamReadiness(name, state);
            const isWaiting = await this.checkpoints.hasUnacknowledgedActiveAssignments(
                state.stream.groupId,
                state.stream.topic,
                state.dataset.datasetGeneration,
            );
            if (isWaiting) {
                return;
            }

            const isCoordinator = await this.datasets.tryBeginReset(name, state.dataset.datasetGeneration);
            if (!isCoordinator) {
                return;
            }

            state.dataset = {...state.dataset, status: 'RESETTING'};
            try {
                await this.datasets.resetProjection(name);
                await this.checkpoints.resetForRebuild(
                    state.stream.groupId,
                    state.stream.topic,
                    state.dataset.baseOffsets,
                    state.dataset.datasetGeneration,
                    state.dataset.sourceGeneration,
                );
                await this.datasets.markRebuilding(name, state.dataset.datasetGeneration);
                state.dataset = {...state.dataset, status: 'REBUILDING'};
            } catch (error) {
                this.metrics.recordRebuild(name, 'failed');
                throw error;
            }
        }

        if (state.dataset.status !== 'REBUILDING'
            || state.activatedDatasetGeneration === state.dataset.datasetGeneration) {
            return;
        }

        const checkpoints = await this.checkpoints.find(state.stream.groupId, state.stream.topic);
        const checkpointByPartition = new Map(checkpoints.map(checkpoint => [checkpoint.partition, checkpoint]));
        for (const [partition, lease] of state.localAssignmentLeases) {
            const checkpoint = checkpointByPartition.get(partition);
            if (!checkpoint || !isSameLease(checkpoint, lease)) {
                return;
            }

            state.checkpoints.set(partition, checkpoint.offset);
            state.startOffsets.set(partition, checkpoint.offset);
            state.checkpointLeases.set(partition, lease);
            state.snapshotBarriers.delete(partition);
            state.snapshotOccurredAt.delete(partition);
            state.invalidRecordOffsets.delete(partition);
            state.consumer.seek({topic: state.stream.topic, partition, offset: checkpoint.offset});
        }

        const partitions = [...state.localAssignmentLeases.keys()];
        if (partitions.length > 0) {
            state.consumer.resume([{topic: state.stream.topic, partitions}]);
        }

        state.activatedDatasetGeneration = state.dataset.datasetGeneration;
        state.targets = state.dataset.targetOffsets.map(({partition, offset}) => ({
            partition,
            low: state.dataset.baseOffsets.find(base => base.partition === partition)?.offset ?? offset,
            high: offset,
            offset,
        }));
        state.catchupStartedAt = Date.now();
        this.states.set(name, state);
    }

    private async activateDataset(name: ProjectionName, state: ProjectionCatchupState): Promise<void> {
        const generation = state.dataset.datasetGeneration;
        try {
            await this.datasets.markActive(name, generation);
            if (state.dataset.datasetGeneration !== generation) {
                return;
            }

            const {mode} = state.dataset;
            state.dataset = {
                ...state.dataset, status: 'ACTIVE', mode: 'INCREMENTAL', reason: undefined,
            };
            state.ready = true;
            this.metrics.recordCatchup(name, mode, (Date.now() - state.catchupStartedAt) / 1000);
            if (mode === 'REBUILD') {
                this.metrics.recordRebuild(name, 'completed');
            }

            this.states.set(name, state);
            if (this.isReady()) {
                this.resolveReady();
            }

            this.logger.log(`Projection dataset activated: ${name} generation=${generation}`);
        } catch (error) {
            this.closeStreamReadiness(name, state);
            this.logger.warn(
                `Projection dataset activation failed [stream=${name}]: `
                + (error instanceof Error ? error.message : String(error)),
            );
        }
    }

    private closeStreamReadiness(name: ProjectionName, state: ProjectionCatchupState): void {
        const wasGloballyReady = this.isReady();
        const wasReady = state.ready;
        state.ready = false;
        this.states.set(name, state);
        if (wasGloballyReady) {
            this.readyPromise = this.createReadyPromise();
        }

        if (wasReady) {
            this.logger.warn(`Kafka projection readiness closed: ${state.stream.topic}`);
        }
    }

    private async createReadyPromise(): Promise<void> {
        return new Promise<void>(resolve => {
            this.resolveReady = resolve;
        });
    }

    private validateStream(stream: ProjectionStream): ProjectionName {
        const name = stream.name as ProjectionName;
        const expected = PROJECTION_STREAMS[name];
        if (expected?.topic !== stream.topic
            || expected.groupId !== stream.groupId
            || expected.expectedSource !== stream.expectedSource
            || expected.sourceGeneration !== stream.sourceGeneration) {
            throw new Error(`Unknown Kafka projection stream: ${stream.name}`);
        }

        return name;
    }
}
