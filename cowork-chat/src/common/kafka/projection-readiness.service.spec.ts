import {type ProjectionCheckpointOffset} from './projection-checkpoint.repository.js';
import {type ProjectionDatasetState} from './projection-dataset.repository.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from './projection-readiness.service.js';
import {isSuspiciousDocumentCountDrop} from './projection-offsets.js';

const BROKER_TOPIC_ID = 'broker-topic';

describe('파일 정리용 동기화 판정', () => {
	it('알려진 지연은 API의 읽기 유예와 관계없이 정리를 막는다', () => {
		const service = new ProjectionReadinessService({} as never, {} as never, {} as never);
		const state: {ready: boolean; lag?: Record<string, unknown>} = {ready: true, lag: {}};
		Object.assign(service, {states: new Map([['meetingNoteAttachment', state]])});
		expect(service.areReady(['meetingNoteAttachment'])).toBe(true);
		expect(service.isCaughtUp('meetingNoteAttachment')).toBe(false);
		delete state.lag;
		expect(service.isCaughtUp('meetingNoteAttachment')).toBe(true);
		state.ready = false;
		expect(service.isCaughtUp('meetingNoteAttachment')).toBe(false);
		expect(service.isCaughtUp('channel')).toBe(false);
	});
});

describe('isSuspiciousDocumentCountDrop', () => {
	it('정상 범위 안의 감소나 증가는 의심하지 않는다', () => {
		expect(isSuspiciousDocumentCountDrop(1000, 995)).toBe(false);
		expect(isSuspiciousDocumentCountDrop(1000, 1200)).toBe(false);
		expect(isSuspiciousDocumentCountDrop(0, 0)).toBe(false);
	});

	it('절대값과 비율을 모두 넘는 감소만 의심한다', () => {
		expect(isSuspiciousDocumentCountDrop(1000, 750)).toBe(true);
		// 절대값은 넘지만 비율이 작은 대용량 컬렉션은 의심하지 않는다.
		expect(isSuspiciousDocumentCountDrop(1_000_000, 999_950)).toBe(false);
		// 비율은 크지만 절대값이 작은 소규모 컬렉션은 의심하지 않는다.
		expect(isSuspiciousDocumentCountDrop(10, 5)).toBe(false);
	});

	it('커스텀 임계값을 그대로 따른다', () => {
		expect(isSuspiciousDocumentCountDrop(10, 5, {minAbsoluteDrop: 3, minRatio: 0.3})).toBe(true);
		expect(isSuspiciousDocumentCountDrop(10, 9, {minAbsoluteDrop: 3, minRatio: 0.3})).toBe(false);
	});
});

describe('시작 시 topic UUID 연속성 판정', () => {
	const stream = PROJECTION_STREAMS.channel;
	const targets = [{
		partition: 0, offset: '20', low: '5', high: '20',
	}];

	async function resolveStartup(input: {datasetTopicId?: string; checkpointTopicId?: string; checkpointOffset?: string; documentCount?: number}) {
		let dataset: ProjectionDatasetState = {
			stream: 'channel',
			groupId: stream.groupId,
			topic: stream.topic,
			...(input.datasetTopicId !== undefined && {topicId: input.datasetTopicId}),
			datasetGeneration: 'dataset-1',
			mode: 'INCREMENTAL',
			status: 'ACTIVE',
			activationDocumentCount: 100,
			lastObservedDocumentCount: 100,
			baseOffsets: [],
			targetOffsets: [],
		};
		const checkpoint: ProjectionCheckpointOffset = {
			partition: 0,
			offset: input.checkpointOffset ?? '10',
			datasetGeneration: 'dataset-1',
			snapshotCompletedOffset: '8',
			...(input.checkpointTopicId !== undefined && {topicId: input.checkpointTopicId}),
		};
		// Mongo 조건부 갱신과 같게, 비어 있는 UUID만 채우고 다른 UUID는 덮어쓰지 않는다.
		const datasets = {
			find: async () => dataset,
			countProjection: async () => input.documentCount ?? 100,
			async markRebuildRequired(_stream: unknown, reason: string) {
				dataset = {...dataset, status: 'REBUILD_REQUIRED', reason};
				return dataset;
			},
			async adoptTopicId(_name: string, generation: string, topicId: string) {
				if (generation !== dataset.datasetGeneration || (dataset.topicId ?? topicId) !== topicId) {
					return undefined;
				}

				dataset = {...dataset, topicId};
				return dataset;
			},
		};
		const checkpoints = {
			findAll: async () => [checkpoint],
			async adoptTopicId(_groupId: string, _topic: string, generation: string, topicId: string) {
				if (checkpoint.datasetGeneration === generation && checkpoint.topicId === undefined) {
					checkpoint.topicId = topicId;
				}
			},
		};
		const service = new ProjectionReadinessService(checkpoints as never, datasets as never, {} as never);
		const resolved = await (service as unknown as {
			resolveStartupDataset(...args: unknown[]): Promise<ProjectionDatasetState>;
		}).resolveStartupDataset('channel', stream, {topicId: BROKER_TOPIC_ID, partitions: targets});
		return {resolved, storedDataset: dataset, storedCheckpoint: checkpoint};
	}

	it('topic UUID가 없는 기존 dataset과 checkpoint는 다른 검사를 통과하면 broker UUID를 넘겨받고 이어 읽는다', async () => {
		const {resolved, storedDataset, storedCheckpoint} = await resolveStartup({});
		expect(resolved).toMatchObject({status: 'ACTIVE', topicId: BROKER_TOPIC_ID});
		expect(storedDataset.topicId).toBe(BROKER_TOPIC_ID);
		expect(storedCheckpoint.topicId).toBe(BROKER_TOPIC_ID);
	});

	it.each([
		['topic UUID가 없는 기존 checkpoint가 retained log 밖이면 재구축한다', {checkpointOffset: '3'}],
		['topic UUID가 없는 기존 dataset의 document 수가 급감했으면 재구축한다', {documentCount: 0}],
		['topic UUID가 없는 기존 dataset이라도 checkpoint의 저장된 UUID가 broker와 다르면 재구축한다', {checkpointTopicId: 'recreated'}],
	])('%s', async (_title, input) => {
		const {resolved, storedDataset} = await resolveStartup(input);
		expect(resolved.status).toBe('REBUILD_REQUIRED');
		expect(storedDataset.topicId).toBeUndefined();
	});

	it('저장된 topic UUID가 broker와 다르면 재구축한다', async () => {
		const {resolved, storedDataset} = await resolveStartup({datasetTopicId: 'recreated', checkpointTopicId: 'recreated'});
		expect(resolved.status).toBe('REBUILD_REQUIRED');
		expect(resolved.reason).toContain('topic UUID mismatch');
		expect(storedDataset.topicId).toBe('recreated');
	});

	it('저장된 topic UUID가 broker와 같으면 그대로 이어 읽는다', async () => {
		const {resolved} = await resolveStartup({datasetTopicId: BROKER_TOPIC_ID, checkpointTopicId: BROKER_TOPIC_ID});
		expect(resolved).toMatchObject({status: 'ACTIVE', topicId: BROKER_TOPIC_ID});
	});
});
