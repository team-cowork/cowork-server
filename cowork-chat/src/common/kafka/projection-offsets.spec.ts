import {type ProjectionDatasetState} from './projection-dataset.repository.js';
import {type ProjectionCheckpointOffset} from './projection-checkpoint.repository.js';
import {
	describeStartupCheckpointProblem,
	hasCompletedSnapshotBarriers,
	isReadyWithLagGrace,
	nextLag,
	PROJECTION_LAG_GRACE_MS,
} from './projection-offsets.js';

const NOW = 1_000_000;

describe('nextLag', () => {
	const at = (high: string) => [{
		partition: 0, offset: high, low: '0', high,
	}];
	const checkpoint = (offset: string) => [{partition: 0, offset}];

	it('현재 high-watermark에 도달하면 lag을 지운다', () => {
		expect(nextLag({since: NOW - 5000, targets: at('10')}, {targets: at('20'), checkpoints: checkpoint('20'), now: NOW})).toBeUndefined();
	});

	it('처음 lag이 보이면 현재 시각과 현재 목표로 시작한다', () => {
		expect(nextLag(undefined, {targets: at('20'), checkpoints: checkpoint('15'), now: NOW})).toEqual({since: NOW, targets: at('20')});
	});

	it('lag 시작 때의 목표에 아직 못 미치면 시작 시각을 유지한다', () => {
		const previous = {since: NOW - 5000, targets: at('20')};
		expect(nextLag(previous, {targets: at('30'), checkpoints: checkpoint('15'), now: NOW})).toBe(previous);
	});

	it('쓰기가 계속돼도 lag 시작 때의 목표에 도달했으면 새 목표로 다시 잰다', () => {
		const previous = {since: NOW - 25_000, targets: at('20')};
		expect(nextLag(previous, {targets: at('30'), checkpoints: checkpoint('22'), now: NOW})).toEqual({since: NOW, targets: at('30')});
	});
});

describe('isReadyWithLagGrace', () => {
	it.each([
		['이미 ready였던 stream은 lag이 유예 시간 안이면 유지한다', true, true, false, 0, true],
		['유예 시간 직전(29.999초)까지는 유지한다', true, true, false, PROJECTION_LAG_GRACE_MS - 1, true],
		['유예 시간(30초)에 닿으면 닫는다', true, true, false, PROJECTION_LAG_GRACE_MS, false],
		['유예 시간을 넘긴 lag은 닫는다', true, true, false, PROJECTION_LAG_GRACE_MS + 1000, false],
		['한 번도 ready가 아니었던 stream은 lag이 짧아도 닫힌 채 둔다', false, true, false, 0, false],
		['lag 추적이 없으면 유예하지 않는다', true, true, false, undefined, false],
		['lag 외의 불변식(fatal, lease, snapshot barrier, dataset 상태)이 깨지면 유예 중이어도 닫는다', true, false, false, 0, false],
		['high-watermark에 도달했고 불변식이 유지되면 ready다', false, true, true, undefined, true],
		['high-watermark에 도달해도 불변식이 깨지면 닫는다', true, false, true, undefined, false],
	])('%s', (...[, wasReady, invariantsHold, hasReachedOffsets, lagMs, expected]) => {
		expect(isReadyWithLagGrace({
			wasReady,
			invariantsHold,
			hasReachedOffsets,
			lag: lagMs === undefined ? undefined : {since: NOW - lagMs},
			now: NOW,
		})).toBe(expected);
	});
});

describe('hasCompletedSnapshotBarriers', () => {
	const targets = [{
		partition: 0, offset: '20', low: '5', high: '20',
	}];
	const checkpoint = (overrides: Partial<ProjectionCheckpointOffset> = {}): ProjectionCheckpointOffset => ({
		partition: 0,
		offset: '12',
		assignmentEpoch: 'epoch-1',
		assignmentMemberId: 'member-1',
		assignmentGenerationId: 3,
		snapshotCompletedOffset: '8',
		...overrides,
	});

	it.each([
		['snapshot marker 뒤로 checkpoint가 전진했으면 통과한다', [checkpoint()], true],
		['checkpoint가 marker와 같으면 아직 전진하지 않은 것이므로 실패한다', [checkpoint({offset: '8'})], false],
		['snapshot marker가 없으면 실패한다', [checkpoint({snapshotCompletedOffset: undefined})], false],
		['marker가 retained low보다 앞이면 실패한다', [checkpoint({snapshotCompletedOffset: '4'})], false],
		['invalid record latch가 걸려 있으면 실패한다', [checkpoint({invalidRecordOffset: '9'})], false],
		['assignment lease 필드가 없으면 실패한다', [checkpoint({assignmentEpoch: undefined})], false],
		['checkpoint row가 없으면 실패한다', [], false],
	])('%s', (_title, checkpoints, expected) => {
		expect(hasCompletedSnapshotBarriers(targets, checkpoints)).toBe(expected);
	});
});

describe('describeStartupCheckpointProblem', () => {
	const targets = [
		{
			partition: 0, offset: '20', low: '5', high: '20',
		},
		{
			partition: 1, offset: '20', low: '5', high: '20',
		},
	];
	const dataset = (overrides: Partial<ProjectionDatasetState> = {}) => {
		const merged: Partial<ProjectionDatasetState> = {
			datasetGeneration: 'dataset-1',
			sourceGeneration: 'source-1',
			status: 'ACTIVE',
			...overrides,
		};
		return merged as ProjectionDatasetState;
	};

	const checkpoint = (partition: number, overrides: Partial<ProjectionCheckpointOffset> = {}): ProjectionCheckpointOffset => ({
		partition,
		offset: '10',
		datasetGeneration: 'dataset-1',
		sourceGeneration: 'source-1',
		snapshotCompletedOffset: '8',
		...overrides,
	});

	it.each([
		['정상 checkpoint는 문제가 없다', dataset(), [checkpoint(0), checkpoint(1)], undefined],
		['dataset generation이 다르면 재구축 사유를 반환한다', dataset(), [checkpoint(0, {datasetGeneration: 'old'}), checkpoint(1)], 'Projection checkpoint generation mismatch: channel.event.v2[0]'],
		['source generation이 다르면 재구축 사유를 반환한다', dataset(), [checkpoint(0), checkpoint(1, {sourceGeneration: 'old'})], 'Projection checkpoint generation mismatch: channel.event.v2[1]'],
		['retained log 밖의 checkpoint는 재구축 사유를 반환한다', dataset(), [checkpoint(0, {offset: '3'}), checkpoint(1)], 'Projection checkpoint is outside retained log: channel.event.v2[0]'],
		['broker에 없는 partition의 checkpoint는 retained log 밖으로 본다', dataset(), [checkpoint(0), checkpoint(2)], 'Projection checkpoint is outside retained log: channel.event.v2[2]'],
		['ACTIVE dataset에서 partition 목록이 불완전하면 실패한다', dataset(), [checkpoint(0)], 'Projection checkpoint partition set is incomplete: stored=0 broker=0,1'],
		['ACTIVE dataset에서 snapshot barrier가 없으면 실패한다', dataset(), [checkpoint(0), checkpoint(1, {snapshotCompletedOffset: undefined})], 'Active projection dataset has no valid snapshot barrier'],
		['초기화 중 dataset은 partition 목록과 barrier를 아직 요구하지 않는다', dataset({status: 'INITIALIZING'}), [checkpoint(0, {snapshotCompletedOffset: undefined})], undefined],
	])('%s', (_title, datasetState, checkpoints, expected) => {
		const problem = describeStartupCheckpointProblem('channel.event.v2', datasetState, checkpoints, targets);
		if (expected === undefined) {
			expect(problem).toBeUndefined();
		} else {
			// 사유 뒤에는 offset 세부 정보가 붙으므로 사유 접두부만 비교한다.
			expect(problem).toContain(expected);
		}
	});
});
