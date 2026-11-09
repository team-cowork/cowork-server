import process from 'node:process';
import {type PartitionOffset} from 'kafkajs';
import {type ProjectionAssignmentLease, type ProjectionCheckpointOffset, type ProjectionCheckpointRepository} from './projection-checkpoint.repository.js';
import {type ProjectionDatasetState} from './projection-dataset.repository.js';
import {type StartupPartitionOffset} from './projection-streams.js';

const MIN_DOCUMENT_COUNT_DROP = Number(process.env.CHAT_PROJECTION_PARTIAL_LOSS_MIN_DOCUMENTS ?? 20);
const MIN_DOCUMENT_COUNT_DROP_RATIO = Number(process.env.CHAT_PROJECTION_PARTIAL_LOSS_MIN_RATIO ?? 0.2);

export function isSameLease(
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
export function compareOffsets(left: string, right: string): number {
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
 생기는 급격한 유실을 구분한다. 전체 유실은 크기와 무관하게 감지하고, 일부 유실에는
 절대값과 비율 임계값을 함께 적용해 작은 변동을 오탐하지 않는다.
 */
export function isSuspiciousDocumentCountDrop(
	baseline: number,
	observed: number,
	options: {minAbsoluteDrop?: number; minRatio?: number} = {},
): boolean {
	if (baseline <= 0 || observed >= baseline) {
		return false;
	}

	if (observed === 0) {
		return true;
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
Upstream이 주기적으로 full snapshot을 다시 발행하므로, 이미 ready였던 stream이 high-watermark를 잠시 못 따라가는 것은 정상이다.
*/
export const PROJECTION_LAG_GRACE_MS = 30_000;

export type ProjectionLag = {
	since: number;
	targets: StartupPartitionOffset[];
};

/**
 현재 high-watermark에 도달하면 lag을 지운다. 쓰기가 계속되면 poll 순간의 high-watermark에는 거의 닿지 못하므로,
 lag이 시작될 때의 목표에 도달했으면 따라가는 중으로 보고 새 목표와 시각으로 다시 잰다.
 */
export function nextLag(
	previous: ProjectionLag | undefined,
	input: {targets: StartupPartitionOffset[]; checkpoints: PartitionOffset[]; now: number},
): ProjectionLag | undefined {
	if (hasReachedStartupOffsets(input.targets, input.checkpoints)) {
		return undefined;
	}

	return previous === undefined || hasReachedStartupOffsets(previous.targets, input.checkpoints)
		? {since: input.now, targets: input.targets}
		: previous;
}

/**
 Lag 외의 불변식이 깨지면 즉시 닫는다. Lag만 있을 때는 이 프로세스에서 이미 ready였던 stream만
 유예 시간 동안 열어 두고, 한 번도 ready가 아니었던 stream은 startup gate대로 high-watermark 도달을 기다린다.
 */
export function isReadyWithLagGrace(input: {
	wasReady: boolean;
	invariantsHold: boolean;
	hasReachedOffsets: boolean;
	lag?: Pick<ProjectionLag, 'since'>;
	now: number;
}): boolean {
	if (!input.invariantsHold) {
		return false;
	}

	if (input.hasReachedOffsets) {
		return true;
	}

	return input.wasReady
		&& input.lag !== undefined
		&& input.now - input.lag.since < PROJECTION_LAG_GRACE_MS;
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
 실행 중 checkpoint를 순서대로 확인해 처음 발견한 복구 불가 사유를 반환한다. 문제가 없으면 `undefined`를 반환한다.
 */
export function describeActiveCheckpointProblem(
	topic: string,
	dataset: Pick<ProjectionDatasetState, 'datasetGeneration' | 'sourceGeneration'>,
	storedCheckpoints: ProjectionCheckpointOffset[],
	currentByPartition: Map<number, StartupPartitionOffset>,
): string | undefined {
	for (const checkpoint of storedCheckpoints) {
		const current = currentByPartition.get(checkpoint.partition);
		if (!current || !isCheckpointWithinRetainedRange(checkpoint.offset, current)) {
			return 'Kafka projection checkpoint is outside the retained log: '
				+ `${topic}[${checkpoint.partition}] checkpoint=${checkpoint.offset} `
				+ `retained=${current?.low ?? 'missing'}-${current?.high ?? 'missing'}`;
		}

		if (checkpoint.datasetGeneration !== dataset.datasetGeneration
			|| checkpoint.sourceGeneration !== dataset.sourceGeneration) {
			return `Kafka projection checkpoint generation mismatch: ${topic}[${checkpoint.partition}]`;
		}
	}

	return undefined;
}

/**
시작 시 저장된 checkpoint가 현재 dataset과 broker 보존 범위에 맞는지 순서대로 확인해, 처음 발견한 재구축 사유를 반환한다.
*/
export function describeStartupCheckpointProblem(
	topic: string,
	dataset: ProjectionDatasetState,
	checkpoints: Awaited<ReturnType<ProjectionCheckpointRepository['findAll']>>,
	targets: StartupPartitionOffset[],
): string | undefined {
	const targetByPartition = new Map(targets.map(target => [target.partition, target]));
	for (const checkpoint of checkpoints) {
		const target = targetByPartition.get(checkpoint.partition);
		if (checkpoint.datasetGeneration !== dataset.datasetGeneration
			|| checkpoint.sourceGeneration !== dataset.sourceGeneration) {
			return `Projection checkpoint generation mismatch: ${topic}[${checkpoint.partition}]`;
		}

		if (!target || !isCheckpointWithinRetainedRange(checkpoint.offset, target)) {
			return `Projection checkpoint is outside retained log: ${topic}[${checkpoint.partition}] `
				+ `checkpoint=${checkpoint.offset} retained=${target?.low ?? 'missing'}-${target?.high ?? 'missing'}`;
		}
	}

	if (dataset.status !== 'ACTIVE') {
		return undefined;
	}

	const partitions = checkpoints.map(({partition}) => partition).toSorted((a, b) => a - b);
	const expected = targets.map(({partition}) => partition).toSorted((a, b) => a - b);
	if (partitions.join(',') !== expected.join(',')) {
		return `Projection checkpoint partition set is incomplete: stored=${partitions.join(',')} `
			+ `broker=${expected.join(',')}`;
	}

	// Invalid-record latch는 rebuild 대상이 아니다. readiness만 닫아 둔 채 소비를 계속하면
	// 서로 다른 두 full snapshot이 latch를 해제한다.
	return checkpoints.some(checkpoint => checkpoint.snapshotCompletedOffset === undefined) ? 'Active projection dataset has no valid snapshot barrier' : undefined;
}
