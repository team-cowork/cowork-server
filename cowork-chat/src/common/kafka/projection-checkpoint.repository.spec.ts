import {
	latchInvalidProjectionRecord,
	observeProjectionRecoverySnapshot,
	type ProjectionRecoveryState,
} from './projection-checkpoint.repository.js';

const SNAPSHOT_A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const SNAPSHOT_B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';

// 표는 latch와 후보 필드만 비교한다. toEqual은 undefined 속성을 무시하므로 부재와 undefined를 같게 본다.
const latchFields = ({invalidRecordOffset, recoverySnapshotId}: ProjectionRecoveryState) => ({invalidRecordOffset, recoverySnapshotId});

describe('latchInvalidProjectionRecord', () => {
	it.each([
		['latch가 없으면 새 gap을 기록하고 이전 후보를 비운다', {recoverySnapshotId: SNAPSHOT_A}, '100', {invalidRecordOffset: '100', recoverySnapshotId: undefined}],
		['같은 offset의 gap은 기존 latch와 후보를 유지한다', {invalidRecordOffset: '100', recoverySnapshotId: SNAPSHOT_A}, '100', {invalidRecordOffset: '100', recoverySnapshotId: SNAPSHOT_A}],
		['더 앞의 offset은 기존 latch를 낮추지 않는다', {invalidRecordOffset: '100', recoverySnapshotId: SNAPSHOT_A}, '99', {invalidRecordOffset: '100', recoverySnapshotId: SNAPSHOT_A}],
		['더 뒤의 gap은 latch를 옮기고 이전 후보를 폐기한다', {invalidRecordOffset: '100', recoverySnapshotId: SNAPSHOT_A}, '120', {invalidRecordOffset: '120', recoverySnapshotId: undefined}],
		['offset은 문자열이 아니라 숫자로 비교한다', {invalidRecordOffset: '999'}, '1000', {invalidRecordOffset: '1000', recoverySnapshotId: undefined}],
	] as Array<[string, ProjectionRecoveryState, string, ProjectionRecoveryState]>)('%s', (_title, state, recordOffset, expected) => {
		expect(latchFields(latchInvalidProjectionRecord(state, recordOffset))).toEqual(expected);
	});
});

describe('observeProjectionRecoverySnapshot', () => {
	it.each([
		['latch 뒤의 첫 snapshot은 후보로만 기록하고 latch는 유지한다', {invalidRecordOffset: '100'}, '150', SNAPSHOT_A, {invalidRecordOffset: '100', recoverySnapshotId: SNAPSHOT_A}],
		['같은 snapshotId가 반복되면 후보를 바꾸지 않는다', {invalidRecordOffset: '100', recoverySnapshotId: SNAPSHOT_A}, '160', SNAPSHOT_A, {invalidRecordOffset: '100', recoverySnapshotId: SNAPSHOT_A}],
		['서로 다른 두 번째 snapshot이 와야 latch를 해제한다', {invalidRecordOffset: '100', recoverySnapshotId: SNAPSHOT_A}, '200', SNAPSHOT_B, {invalidRecordOffset: undefined, recoverySnapshotId: undefined}],
		['latch 이전 marker는 recovery 후보가 되지 않는다', {invalidRecordOffset: '100'}, '100', SNAPSHOT_A, {invalidRecordOffset: '100', recoverySnapshotId: undefined}],
		['직전 snapshot과 같은 id는 recovery 후보가 되지 않는다', {invalidRecordOffset: '100', lastSnapshotId: SNAPSHOT_A}, '150', SNAPSHOT_A, {invalidRecordOffset: '100', recoverySnapshotId: undefined}],
		['latch가 없으면 recovery 후보를 기록하지 않는다', {}, '150', SNAPSHOT_A, {invalidRecordOffset: undefined, recoverySnapshotId: undefined}],
	] as Array<[string, ProjectionRecoveryState, string, string, ProjectionRecoveryState]>)('%s', (...[, state, markerOffset, snapshotId, expected]) => {
		expect(latchFields(observeProjectionRecoverySnapshot(state, markerOffset, snapshotId))).toEqual(expected);
	});

	it('완료 marker를 offset 순서대로만 마지막 snapshot으로 기록한다', () => {
		const later = observeProjectionRecoverySnapshot({}, '150', SNAPSHOT_A);
		expect(later).toMatchObject({lastSnapshotCompletedOffset: '150', lastSnapshotId: SNAPSHOT_A});

		const older = observeProjectionRecoverySnapshot(later, '120', SNAPSHOT_B);
		expect(older).toMatchObject({lastSnapshotCompletedOffset: '150', lastSnapshotId: SNAPSHOT_A});
	});
});
