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
