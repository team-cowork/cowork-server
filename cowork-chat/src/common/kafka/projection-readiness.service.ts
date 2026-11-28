import process from 'node:process';
import {
	BadRequestException,
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
} from '@nestjs/common';
import {
	Admin,
	type AdminOptions,
	type ConsumerGroupJoinPayload,
	type ConsumerHeartbeatPayload,
	ListOffsetTimestamps,
} from '@platformatic/kafka';
import {type Nullable} from '../type/nullable.js';
import {
	ProjectionAssignmentLease,
	ProjectionCheckpointFenceError,
	ProjectionCheckpointRepository,
	SnapshotBarrierReceipt,
} from './projection-checkpoint.repository.js';
import {
	ProjectionDatasetRepository,
	ProjectionDatasetState,
	ProjectionStreamName,
} from './projection-dataset.repository.js';
import {ProjectionMetricsService} from './projection-metrics.service.js';
import {type PartitionFetcher} from './partition-fetcher.js';
import {
	compareOffsets,
	describeStartupCheckpointProblem,
	describeActiveCheckpointProblem,
	describeTopicIdentityProblem,
	hasCompletedSnapshotBarriers,
	hasReachedStartupOffsets,
	isCheckpointWithinRetainedRange,
	isReadyWithLagGrace,
	isSameLease,
	nextLag,
	isSuspiciousDocumentCountDrop,
} from './projection-offsets.js';
import {
	PROJECTION_STREAMS,
	type ProjectionName,
	type ProjectionStream,
	type StartupPartitionOffset,
} from './projection-streams.js';
import {
	type AssignmentClaimContext,
	type ClaimedAssignment,
	type PendingProjectionAssignment,
	type ProjectionAdminConnection,
	type ProjectionCatchupState,
	type ProjectionStreamStatus,
} from './projection-readiness.types.js';

export {
	CHANNEL_PERMISSION_STREAMS,
	PROJECTION_STREAMS,
	type ProjectionName,
	type ProjectionStream,
	type StartupPartitionOffset,
} from './projection-streams.js';

const POLL_INTERVAL_MS = 1000;
/**
 매 poll(1초)마다 document count를 정확히 세면 대용량 컬렉션에 상시 부하를 준다. 외부 개입으로 인한
 유실은 초 단위로 빠르게 잡을 이유가 없으므로, 훨씬 느슨한 주기로만 추정치를 다시 확인한다.
 */
const DOCUMENT_COUNT_CHECK_INTERVAL_MS = Number(
	process.env.CHAT_PROJECTION_DOCUMENT_COUNT_CHECK_INTERVAL_MS ?? 60_000,
);
/**
 Kafka client는 커넥션의 inflight 상한에 막혀 대기열에 남은 요청에는 request timeout을 적용하지 않아
 한 번 막힌 admin 커넥션이 영원히 응답하지 않을 수 있다.
 상한을 넘긴 요청은 실패시키고 admin을 폐기해 다음 호출이 새 커넥션을 쓰게 한다.
 */
const ADMIN_REQUEST_TIMEOUT_MS = 15_000;
/**
 막힌 커넥션의 close도 응답하지 않을 수 있으므로 종료 대기에 상한을 둔다. 상한이 없으면
 graceful shutdown이 termination grace period 만료(SIGKILL)까지 밀린다.
 */
const ADMIN_DISCONNECT_TIMEOUT_MS = 2000;

/**
 Topic ID를 모르는 broker가 돌려주는 값.
 */
const ZERO_TOPIC_ID = '00000000-0000-0000-0000-000000000000';

const REBUILD_PENDING_STATUSES = new Set<ProjectionDatasetState['status']>(['REBUILD_REQUIRED', 'REBUILD_REQUESTED', 'RESETTING']);

type TopicOffsets = {
	topicId: string;
	partitions: StartupPartitionOffset[];
};

/**
 Broker topic UUID와 파티션별 보존 범위를 읽는다. 읽는 사이에 topic이 다시 만들어지면 offset이 어느 topic의
 것인지 알 수 없으므로 실패하고, 호출자가 다음 poll에서 다시 읽는다.
 */
async function readTopicOffsets(admin: Admin, topic: string): Promise<TopicOffsets> {
	const readMetadata = async () => {
		const metadata = await admin.metadata({topics: [topic], forceUpdate: true});
		const topicMetadata = metadata.topics.get(topic);
		if (topicMetadata === undefined || topicMetadata.id === '' || topicMetadata.id === ZERO_TOPIC_ID) {
			throw new Error(`Kafka topic metadata has no topic UUID: ${topic}`);
		}

		return topicMetadata;
	};

	const before = await readMetadata();
	const partitions = Array.from({length: before.partitionsCount}, (_, partition) => partition);
	const [earliest, latest] = await Promise.all([ListOffsetTimestamps.EARLIEST, ListOffsetTimestamps.LATEST].map(async timestamp => {
		const listed = await admin.listOffsets({
			topics: [{name: topic, partitions: partitions.map(partitionIndex => ({partitionIndex, timestamp}))}],
		});
		return new Map(listed.flatMap(({partitions: listedPartitions}) => listedPartitions)
			.map(({partitionIndex, offset}) => [partitionIndex, offset.toString()]));
	}));
	const after = await readMetadata();
	if (after.id !== before.id || after.partitionsCount !== before.partitionsCount) {
		throw new Error(`Kafka topic changed while reading offsets: ${topic}`);
	}

	return {
		topicId: before.id,
		partitions: partitions.map(partition => {
			const high = latest.get(partition);
			const low = earliest.get(partition);
			if (high === undefined || low === undefined) {
				throw new Error(`Kafka topic offsets are missing: ${topic}[${partition}]`);
			}

			return {
				partition, offset: high, high, low,
			};
		}),
	};
}

/**
 Rebuild 요청은 그때의 broker topic UUID를 dataset에 기록하므로, rebuild 중인 dataset에는 항상 값이 있다.
 */
function requireTopicId(dataset: ProjectionDatasetState): string {
	if (dataset.topicId === undefined) {
		throw new Error(`Projection dataset has no topic UUID: ${dataset.stream}`);
	}

	return dataset.topicId;
}

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

/**
 Kafka projection consumer들의 shared Mongo replay barrier.

 consumer가 실행되기 직전에 토픽의 high-watermark와 Mongo checkpoint를 캡처하고, group
 assignment 때 broker group offset 대신 checkpoint(없으면 earliest)로 seek한다. projection
 반영 뒤 checkpoint를 저장하므로 Mongo가 초기화된 환경에서도 기존 broker group commit이
 이벤트 replay를 건너뛰지 못한다.
 */
@injectable()
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

	private async resolveStartupDataset(
		name: ProjectionName,
		stream: ProjectionStream,
		{topicId, partitions: targets}: TopicOffsets,
	): Promise<ProjectionDatasetState> {
		// Read the dataset first so checkpoints cannot predate an already observed rebuild generation.
		const dataset = await this.datasets.find(name);
		const [checkpoints, documentCount] = await Promise.all([
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
				topicId,
				targets.map(({partition, low}) => ({partition, offset: low})),
				targets.map(({partition, high}) => ({partition, offset: high})),
			);
		}

		if (dataset.groupId !== stream.groupId || dataset.topic !== stream.topic) {
			return this.datasets.markRebuildRequired(stream, 'Projection dataset stream identity changed', dataset);
		}

		// Topic UUID를 저장하기 전에 기록된 기존 row는 broker UUID를 가진 것으로 보고 검사해, 모두 통과하면 그 UUID를 넘겨받는다.
		// 진행 중인 rebuild는 현재 topic을 처음부터 다시 읽으므로 바로 넘겨받고, REBUILD_REQUIRED는 운영자의 rebuild 요청을 기다린다.
		const isLegacy = dataset.topicId === undefined && dataset.status !== 'REBUILD_REQUIRED';
		const checked = isLegacy ? {...dataset, topicId} : dataset;
		const topicProblem = describeTopicIdentityProblem(stream.topic, checked, topicId);
		if (topicProblem !== undefined) {
			return this.datasets.markRebuildRequired(stream, topicProblem, dataset);
		}

		if (REBUILD_PENDING_STATUSES.has(dataset.status)) {
			return isLegacy ? this.adoptLegacyTopicId(name, stream, dataset, topicId) : dataset;
		}

		if (dataset.status === 'ACTIVE') {
			const baseline = dataset.lastObservedDocumentCount ?? dataset.activationDocumentCount;
			if (isSuspiciousDocumentCountDrop(baseline, documentCount)) {
				return this.datasets.markRebuildRequired(
					stream,
					'Projection collection lost documents since it was last observed '
					+ `(baseline=${baseline}, observed=${documentCount})`,
					dataset,
				);
			}
		}

		const checkpointProblem = describeStartupCheckpointProblem(
			stream.topic,
			checked,
			isLegacy ? checkpoints.map(checkpoint => ({...checkpoint, topicId: checkpoint.topicId ?? topicId})) : checkpoints,
			targets,
		);
		if (checkpointProblem !== undefined) {
			return this.datasets.markRebuildRequired(stream, checkpointProblem, dataset);
		}

		return isLegacy ? this.adoptLegacyTopicId(name, stream, dataset, topicId) : dataset;
	}

	private async adoptLegacyTopicId(
		name: ProjectionName,
		stream: ProjectionStream,
		dataset: ProjectionDatasetState,
		topicId: string,
	): Promise<ProjectionDatasetState> {
		this.logger.log(`Adopting broker topic UUID for legacy projection dataset: ${stream.topic} status=${dataset.status} topicId=${topicId}`);
		// Checkpoint를 먼저 기록해, 중간에 멈추면 dataset이 기존 row로 남아 다음 시작에서 다시 넘겨받게 한다.
		await this.checkpoints.adoptTopicId(stream.groupId, stream.topic, dataset.datasetGeneration, topicId);
		const adopted = await this.datasets.adoptTopicId(name, dataset.datasetGeneration, topicId);
		return adopted ?? this.datasets.markRebuildRequired(stream, 'Projection topic UUID adoption was superseded', dataset);
	}

	private async captureStartupState(
		fetcher: PartitionFetcher,
		name: ProjectionName,
		stream: ProjectionStream,
	): Promise<void> {
		const topicOffsets = await this.withAdmin(
			name,
			async admin => readTopicOffsets(admin, stream.topic),
			`readTopicOffsets(${stream.topic})`,
		);
		const targets = topicOffsets.partitions;
		if (targets.length === 0) {
			throw new Error(`Kafka projection topic has no partitions: ${stream.topic}`);
		}

		const dataset = await this.resolveStartupDataset(name, stream, topicOffsets);
		const state: ProjectionCatchupState = {
			stream,
			fetcher,
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
		this.attachAssignmentFence(fetcher, name, state);
		this.logger.log(
			`Projection startup offsets captured: ${stream.topic} topicId=${topicOffsets.topicId} mode=${dataset.mode} status=${dataset.status} `
			+ `[${targets.map(({partition, low, high}) => `${partition}:${low}-${high}`).join(', ')}]`,
		);
		this.schedulePoll(name);
	}

	/**
	 새 generation에 배정된 파티션은 fetcher가 시작 위치 없이(`claiming`) 멈춰 두므로, lease claim과 seek 뒤에만 fetch한다.
	 */
	private attachAssignmentFence(
		fetcher: PartitionFetcher,
		name: ProjectionName,
		state: ProjectionCatchupState,
	): void {
		const {consumer} = fetcher;
		consumer.on('consumer:group:join', (payload: ConsumerGroupJoinPayload) => {
			if (this.stopped || payload.groupId !== state.stream.groupId) {
				return;
			}

			const assignedPartitions = payload.assignments?.find(({topic}) => topic === state.stream.topic)?.partitions ?? [];
			const revision = state.assignmentRevision + 1;
			const previouslyOwned = [...state.localAssignmentLeases];
			state.assignmentRevision = revision;
			state.assignmentObserved = false;
			state.leaseRenewalPaused = false;
			state.localAssignmentLeases.clear();
			state.pendingAssignment = {
				revision,
				memberId: payload.memberId,
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
			this.enqueueAssignmentTask(state, async () => this.releaseAssignments(state, previouslyOwned), error => {
				this.closeStreamReadiness(name, state);
				this.logger.warn(
					`Kafka projection previous lease release failed [topic=${state.stream.topic}]: `
					+ (error instanceof Error ? error.message : String(error)),
				);
			});
		});

		consumer.on('consumer:heartbeat:end', (payload?: ConsumerHeartbeatPayload) => {
			this.handleHeartbeat(name, state, payload);
		});
		consumer.on('consumer:group:rebalance', () => {
			this.revokeAssignments(name, state, 'rebalance');
		});
		consumer.on('consumer:group:leave', () => {
			this.revokeAssignments(name, state, 'leave');
		});
		consumer.on('consumer:group:lost', () => {
			this.revokeAssignments(name, state, 'group lost');
		});
	}

	private handleHeartbeat(
		name: ProjectionName,
		state: ProjectionCatchupState,
		payload: ConsumerHeartbeatPayload | undefined,
	): void {
		if (this.stopped || payload?.groupId !== state.stream.groupId) {
			return;
		}

		state.heartbeatSequence += 1;
		const {heartbeatSequence} = state;
		const pending = state.pendingAssignment;
		if (pending) {
			if (heartbeatSequence <= pending.afterHeartbeatSequence
				|| payload.memberId !== pending.memberId || payload.generationId === undefined
				|| (pending.groupGenerationId !== undefined && pending.groupGenerationId !== payload.generationId)) {
				return;
			}

			pending.groupGenerationId = payload.generationId;
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
					|| message.includes('assigned partition is missing')) {
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
		if (payload.memberId !== currentLease.memberId) {
			return;
		}

		if (payload.generationId !== currentLease.groupGenerationId) {
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
					state.fetcher.resume(owned.map(([partition]) => partition));
					state.leaseRenewalPaused = false;
				}

				this.schedulePoll(name);
			} finally {
				state.renewalInFlight = false;
			}
		}, error => {
			state.renewalInFlight = false;
			this.closeStreamReadiness(name, state);
			const partitions = state.localAssignmentLeases.keys().toArray();
			if (partitions.length > 0) {
				state.fetcher.pause(partitions);
				state.leaseRenewalPaused = true;
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

		const {topicId, partitions: currentOffsets} = await this.withAdmin(
			state.stream.name as ProjectionName,
			async admin => readTopicOffsets(admin, state.stream.topic),
			`readTopicOffsets(${state.stream.topic})`,
		);
		if (state.pendingAssignment !== pending || pending.revision !== state.assignmentRevision) {
			return;
		}

		const topicProblem = describeTopicIdentityProblem(state.stream.topic, dataset, topicId);
		if (topicProblem !== undefined) {
			await this.markStreamUnrecoverable(state.stream.name as ProjectionName, state, topicProblem);
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

		const context: AssignmentClaimContext = {
			state,
			pending,
			groupGenerationId: pending.groupGenerationId,
			dataset,
			topicId,
			currentByPartition: new Map(currentOffsets.map(offset => [offset.partition, offset])),
		};
		const claimed: ClaimedAssignment[] = [];
		let isComplete: boolean;
		try {
			isComplete = await this.claimPartitionsInOrder(context, 0, claimed);
		} catch (error) {
			await this.releaseAssignments(
				state,
				claimed.map(({partition, lease}) => [partition, lease]),
			).catch(() => undefined);
			throw error;
		}

		if (!isComplete) {
			return;
		}

		if (state.pendingAssignment !== pending || pending.revision !== state.assignmentRevision) {
			await this.releaseAssignments(
				state,
				claimed.map(({partition, lease}) => [partition, lease]),
			);
			return;
		}

		this.activateClaimedAssignments(context, currentOffsets, claimed);

		this.schedulePoll(state.stream.name as ProjectionName);
	}

	/**
	 배정된 partition을 순서대로 claim한다. 앞 partition의 claim 결과에 따라 중단하므로 순차로 실행한다.
	 claim에 성공한 lease는 `claimed`에 쌓아, 실패하면 호출자가 해제할 수 있게 한다.
	 중단하면 이미 해제·기록을 마친 상태이므로 `false`를 반환한다.
	 */
	private async claimPartitionsInOrder(
		context: AssignmentClaimContext,
		index: number,
		claimed: ClaimedAssignment[],
	): Promise<boolean> {
		const {pending} = context;
		if (index >= pending.assignedPartitions.length) {
			return true;
		}

		const {state} = context;
		if (state.pendingAssignment !== pending || pending.revision !== state.assignmentRevision) {
			await this.releaseAssignments(
				state,
				claimed.map(({partition: claimedPartition, lease}) => [claimedPartition, lease]),
			);
			return false;
		}

		const partition = pending.assignedPartitions[index];

		const target = context.currentByPartition.get(partition);
		if (!target) {
			throw new Error(`assigned partition is missing from broker metadata: ${partition}`);
		}

		const {dataset} = context;
		const claim = await this.checkpoints.claimForAssignment({
			groupId: state.stream.groupId,
			topic: state.stream.topic,
			partition,
			earliestOffset: target.low,
			datasetGeneration: dataset.datasetGeneration,
			topicId: context.topicId,
			memberId: pending.memberId,
			groupGenerationId: context.groupGenerationId,
		});
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
			return false;
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
			return false;
		}

		claimed.push({
			partition, target, lease: claim.lease, checkpoint: claim.checkpoint,
		});
		return this.claimPartitionsInOrder(context, index + 1, claimed);
	}

	private activateClaimedAssignments(
		context: AssignmentClaimContext,
		currentOffsets: StartupPartitionOffset[],
		claimed: ClaimedAssignment[],
	): void {
		const {state, pending, dataset, topicId} = context;
		state.targets = currentOffsets;
		state.assignmentObserved = true;
		state.pendingAssignment = undefined;
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

			state.fetcher.seek(partition, {topicId, offset: BigInt(checkpoint.offset)});
			this.logger.log(
				`Projection assignment fenced and resumed: ${state.stream.topic}[${partition}] `
				+ `mode=${dataset.mode} offset=${checkpoint.offset}`,
			);
		}

		state.fetcher.resume(pending.assignedPartitions);
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
     blocking으로 묶인다. Admin은 첫 요청에서 연결한다.
     */
	private getAdmin(name: ProjectionName): Admin {
		const connection = this.admins.get(name);
		if (!connection) {
			throw new Error(`Kafka projection admin is not registered: ${name}`);
		}

		connection.admin ??= new Admin(connection.options);
		return connection.admin;
	}

	/**
     Admin 요청에 상한을 두고, 상한을 넘기면 해당 admin을 폐기해 다음 호출이 재연결하게 한다.
     */
	private async withAdmin<T>(
		name: ProjectionName,
		run: (admin: Admin) => Promise<T>,
		operation: string,
	): Promise<T> {
		const admin = this.getAdmin(name);
		try {
			return await withTimeout(
				run(admin),
				ADMIN_REQUEST_TIMEOUT_MS,
				`Kafka projection admin request timed out after ${ADMIN_REQUEST_TIMEOUT_MS}ms `
				+ `[stream=${name}, operation=${operation}]`,
			);
		} catch (error) {
			if (error instanceof ProjectionOperationTimeoutError) {
				this.resetAdmin(name, admin);
			}

			throw error;
		}
	}

	/**
    막힌 admin을 등록에서 떼어낸다. close 자체도 응답하지 않을 수 있어 대기하지 않는다.
    */
	private resetAdmin(name: ProjectionName, staleAdmin?: Admin): void {
		const connection = this.admins.get(name);
		const admin = connection?.admin;
		if (!connection || !admin || (staleAdmin && admin !== staleAdmin)) {
			return;
		}

		connection.admin = undefined;
		this.logger.warn(`Kafka projection admin connection is being recycled [stream=${name}]`);
		void admin.close().catch(() => undefined);
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

			// Fence 검사는 살아 있는 lease만 보고, 보존 범위·generation 검사와 진행 판정은 lease가 만료된 partition도 포함한다.
			// 그래야 replica가 비정상 종료해도 재할당 전까지 snapshot barrier 판정에서 partition이 빠지지 않는다.
			const [storedCheckpoints, allCheckpoints] = await Promise.all([
				this.checkpoints.find(state.stream.groupId, state.stream.topic),
				this.checkpoints.findAll(state.stream.groupId, state.stream.topic),
			]);
			if (assignmentRevision !== state.assignmentRevision) {
				return;
			}

			const {topicId, partitions: currentOffsets} = await this.withAdmin(
				name,
				async admin => readTopicOffsets(admin, state.stream.topic),
				`readTopicOffsets(${state.stream.topic})`,
			);
			if (assignmentRevision !== state.assignmentRevision) {
				return;
			}

			const topicProblem = describeTopicIdentityProblem(state.stream.topic, state.dataset, topicId);
			if (topicProblem !== undefined) {
				await this.markStreamUnrecoverable(name, state, topicProblem);
				return;
			}

			const checkpointProblem = describeActiveCheckpointProblem(
				state.stream.topic,
				state.dataset,
				allCheckpoints,
				new Map(currentOffsets.map(offset => [offset.partition, offset])),
			);
			if (checkpointProblem !== undefined) {
				await this.markStreamUnrecoverable(name, state, checkpointProblem);
				return;
			}

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
			state.checkpoints = new Map(allCheckpoints.map(({partition, offset}) => [partition, offset]));
			state.checkpointLeases = new Map(
				allCheckpoints.flatMap(({
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
				allCheckpoints.flatMap(({partition, snapshotCompletedOffset}) => (
					snapshotCompletedOffset === undefined ? [] : [[partition, snapshotCompletedOffset] as const]
				)),
			);
			state.snapshotOccurredAt = new Map(
				allCheckpoints.flatMap(({partition, snapshotOccurredAt}) => (
					snapshotOccurredAt === undefined ? [] : [[partition, snapshotOccurredAt] as const]
				)),
			);
			state.invalidRecordOffsets = new Map(
				allCheckpoints.flatMap(({partition, invalidRecordOffset}) => (
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
		const isInvariantsHold = this.fatalInvariantReason === undefined
			&& state.assignmentObserved
			&& !state.leaseRenewalPaused
			&& hasCompletedSnapshotBarriers(state.targets, checkpointState)
			&& isFreshRebuildSnapshot;
		const hasReachedOffsets = hasReachedStartupOffsets(state.targets, checkpointOffsets);
		const now = Date.now();
		state.lag = nextLag(state.lag, {targets: state.targets, checkpoints: checkpointOffsets, now});
		state.ready = isReadyWithLagGrace({
			wasReady,
			invariantsHold: isInvariantsHold && state.dataset.status === 'ACTIVE',
			hasReachedOffsets,
			lag: state.lag,
			now,
		});
		if (isInvariantsHold && hasReachedOffsets && (state.dataset.status === 'INITIALIZING' || state.dataset.status === 'REBUILDING')) {
			await this.activateDataset(name, state);
		}

		this.states.set(name, state);
		if (!wasReady && state.ready) {
			this.logger.log(`Kafka projection caught up: ${state.stream.topic}`);
		} else if (wasReady && !state.ready) {
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
		state.dataset = await this.datasets.markRebuildRequired(state.stream, reason, state.dataset);
		this.closeStreamReadiness(name, state);
		state.fetcher.pause(state.localAssignmentLeases.keys().toArray());
		this.logger.error(`Projection rebuild required [stream=${name}]: ${reason}`);
	}

	private async driveRebuild(name: ProjectionName, state: ProjectionCatchupState): Promise<void> {
		if (state.dataset.status === 'REBUILD_REQUESTED') {
			const owned = [...state.localAssignmentLeases];
			if (owned.length > 0) {
				state.fetcher.pause(owned.map(([partition]) => partition));
				await Promise.all(owned.map(async ([partition, lease]) => this.checkpoints.acknowledgeRebuildPause({
					groupId: state.stream.groupId, topic: state.stream.topic, partition, lease, datasetGeneration: state.dataset.datasetGeneration,
				},
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
				await this.checkpoints.resetForRebuild({
					groupId: state.stream.groupId,
					topic: state.stream.topic,
					offsets: state.dataset.baseOffsets,
					datasetGeneration: state.dataset.datasetGeneration,
					topicId: requireTopicId(state.dataset),
				});
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

		const topicId = requireTopicId(state.dataset);
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
			state.fetcher.seek(partition, {topicId, offset: BigInt(checkpoint.offset)});
		}

		state.fetcher.resume(state.localAssignmentLeases.keys().toArray());

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
			|| expected.expectedSource !== stream.expectedSource) {
			throw new Error(`Unknown Kafka projection stream: ${stream.name}`);
		}

		return name;
	}

	async registerProjection(fetcher: PartitionFetcher, stream: ProjectionStream, adminOptions: AdminOptions): Promise<void> {
		const name = this.validateStream(stream);
		const existing = this.registrations.get(name);
		if (existing) {
			return existing;
		}

		this.admins.set(name, {options: adminOptions});
		const registration = this.captureStartupState(fetcher, name, stream).catch((error: unknown) => {
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
				await this.checkpoints.advance({
					groupId: stream.groupId, topic: stream.topic, partition, lease: assignmentLease, nextOffset, snapshotBarrier: result.snapshotBarrier,
				});
			} else {
				await this.checkpoints.advance({
					groupId: stream.groupId, topic: stream.topic, partition, lease: assignmentLease, nextOffset,
				});
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
		record: {
			stream: ProjectionStream;
			partition: number;
			offset: string;
			eventKey: Nullable<string>;
			payload: Nullable<string>;
			reason: string;
		},

	): Promise<void> {
		const {stream, partition, offset, eventKey, payload, reason} = record;
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
		await this.checkpoints.quarantine({
			groupId: stream.groupId, topic: stream.topic, partition, offset, eventKey, payload, reason,
		});
		if (state.localAssignmentLeases.get(partition) !== assignmentLease) {
			throw new Error(`Kafka projection assignment changed while quarantining: ${stream.topic}[${partition}]`);
		}

		try {
			await this.checkpoints.markInvalidRecord({
				groupId: stream.groupId, topic: stream.topic, partition, lease: assignmentLease, recordOffset: offset,
			});
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

	// 물리 삭제는 API 읽기용 지연 유예를 사용하지 않는다.
	isCaughtUp(name: ProjectionName): boolean {
		const state = this.states.get(name);
		return state?.ready === true && state.lag === undefined;
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
			...(state.dataset.topicId !== undefined && {topicId: state.dataset.topicId}),
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

		const {topicId, partitions: offsets} = await this.withAdmin(
			name,
			async admin => readTopicOffsets(admin, state.stream.topic),
			`readTopicOffsets(${state.stream.topic})`,
		);
		const dataset = await this.datasets.requestRebuild(state.stream, {
			reason,
			topicId,
			baseOffsets: offsets.map(({partition, low}) => ({partition, offset: low})),
			targetOffsets: offsets.map(({partition, high}) => ({partition, offset: high})),
		},
		);
		state.dataset = dataset;
		// 전체 준비 promise도 새로 만들어야 `whenReady()`가 이미 끝난 promise를 돌려주지 않는다.
		this.closeStreamReadiness(name, state);
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

		// 확인 작업이 상태를 바꿔도 시작 시점의 stream 목록만 확인하도록 먼저 복사한다.
		const names = this.states.keys().toArray();
		await Promise.all(names.map(async name => this.checkStream(name)));
	}

	async onModuleDestroy(): Promise<void> {
		this.stopped = true;
		for (const state of this.states.values()) {
			if (state.pollTimer) {
				clearTimeout(state.pollTimer);
			}

			state.pollTimer = undefined;
		}

		const states = this.states.values().toArray();
		await Promise.all(states.map(async state => {
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

		// Close도 응답하지 않을 수 있어(resetAdmin과 같은 판단) 상한을 두고 기다린다.
		const connections = this.admins.values().toArray();
		await Promise.all(connections.map(async connection => {
			const {admin} = connection;
			connection.admin = undefined;
			if (!admin) {
				return;
			}

			await withTimeout(
				admin.close(),
				ADMIN_DISCONNECT_TIMEOUT_MS,
				`Kafka projection admin close timed out after ${ADMIN_DISCONNECT_TIMEOUT_MS}ms`,
			).catch(() => undefined);
		}));
		this.admins.clear();
	}
}
