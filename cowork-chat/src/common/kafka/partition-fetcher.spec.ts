import {Buffer} from 'node:buffer';
import {setInterval, setTimeout as delay} from 'node:timers/promises';
import {type Logger} from '@nestjs/common';
import {type Consumer, ListOffsetTimestamps, protocolErrors} from '@platformatic/kafka';
import {createCommittedFetcher, type FetchPosition, PartitionFetcher} from './partition-fetcher.js';

const topic = 'test.topic';
const topicId = 'test-topic-uuid';

/**
 Partition마다 메모리 log를 두고, 요청한 offset부터 record를 돌려주는 broker 대역.
 */
class FakeConsumer {
	private readonly listeners = new Map<string, Array<(payload: unknown) => void>>();
	readonly topics = {track: jest.fn()};
	readonly logs = new Map<number, string[]>([[0, []], [1, []]]);
	readonly logStart = new Map<number, number>();
	readonly fetchOffsets: Array<[number, bigint]> = [];
	readonly committed = new Map<number, bigint>();
	readonly commits: bigint[] = [];
	/**
	 비우지 않으면 커밋 offset 조회가 이 promise를 하나씩 꺼내 기다린다.
	 */
	readonly lookupGates: Array<Promise<void>> = [];
	generationId?: number;
	leaders = [1, 1];
	failingNode?: number;

	async joinGroup(): Promise<string> {
		return 'member';
	}

	async close(): Promise<void> {
		this.listeners.clear();
	}

	on(event: string, listener: (payload: unknown) => void): void {
		this.listeners.set(event, [...(this.listeners.get(event) ?? []), listener]);
	}

	emit(event: string, payload: unknown): void {
		for (const listener of this.listeners.get(event) ?? []) {
			listener(payload);
		}
	}

	async metadata() {
		return {topics: new Map([[topic, {id: topicId, partitions: this.leaders.map(leader => ({leader}))}]])};
	}

	async listOffsets({partitions, timestamp}: {partitions: Record<string, number[]>; timestamp: bigint}) {
		const offsets: bigint[] = [];
		for (const partition of partitions[topic]) {
			offsets[partition] = BigInt(timestamp === ListOffsetTimestamps.EARLIEST
				? this.logStart.get(partition) ?? 0
				: this.logs.get(partition)!.length);
		}

		return new Map([[topic, offsets]]);
	}

	async listCommittedOffsets({topics}: {topics: Array<{partitions: number[]}>}) {
		const offsets: bigint[] = [];
		for (const partition of topics[0].partitions) {
			offsets[partition] = this.committed.get(partition) ?? -1n;
		}

		await this.lookupGates.shift();
		return new Map([[topic, offsets]]);
	}

	async commit({offsets}: {offsets: Array<{offset: bigint}>}): Promise<void> {
		this.commits.push(...offsets.map(({offset}) => offset));
	}

	async fetch({node, topics}: {node: number; topics: Array<{topicId: string; partitions: Array<{partition: number; fetchOffset: bigint}>}>}) {
		await delay(5);
		if (node === this.failingNode) {
			throw new Error(`broker ${node} unavailable`);
		}

		return {
			responses: topics.map(request => ({
				topicId: request.topicId,
				partitions: request.partitions.map(({partition, fetchOffset}) => {
					this.fetchOffsets.push([partition, fetchOffset]);
					if (fetchOffset < BigInt(this.logStart.get(partition) ?? 0)) {
						return {partitionIndex: partition, errorCode: protocolErrors.OFFSET_OUT_OF_RANGE.code, records: []};
					}

					const values = this.logs.get(partition)!.slice(Number(fetchOffset));
					return {
						partitionIndex: partition,
						errorCode: 0,
						records: values.length === 0
							? []
							: [{
								firstOffset: fetchOffset,
								lastOffsetDelta: values.length - 1,
								attributes: 0,
								records: values.map((value, index) => ({offsetDelta: index, key: null, value: Buffer.from(value)})),
							}],
					};
				}),
			})),
		};
	}

	assign(partitions: number[], generationId = 1): void {
		this.generationId = generationId;
		this.emit('consumer:group:join', {
			groupId: 'group', memberId: 'member', generationId, assignments: [{topic, partitions}],
		});
	}
}

const logger = {
	log: jest.fn(), warn: jest.fn(), error: jest.fn(),
} as unknown as Logger;

async function waitFor(isMet: () => boolean, timeoutMs = 3000): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	for await (const _tick of setInterval(5)) {
		if (isMet()) {
			return;
		}

		if (Date.now() > deadline) {
			throw new Error('condition was not met in time');
		}
	}
}

function createFetcher(options: {
	handle?: (value: string, partition: number) => Promise<void>;
	resetTo?: 'earliest' | 'latest';
	onProgress?: (partition: number, position: FetchPosition) => Promise<void>;
	concurrency?: number;
} = {}) {
	const consumer = new FakeConsumer();
	const handled: string[] = [];
	const fetcher = new PartitionFetcher({
		logger,
		consumer: consumer as unknown as Consumer,
		topic,
		concurrency: options.concurrency ?? 1,
		async handle(record) {
			await options.handle?.(record.value!, record.partition);
			handled.push(record.value!);
		},
		...(options.resetTo !== undefined && {resetTo: options.resetTo}),
		...(options.onProgress !== undefined && {onProgress: options.onProgress}),
	});
	return {consumer, fetcher, handled};
}

describe('PartitionFetcher', () => {
	const fetchers: PartitionFetcher[] = [];
	afterEach(async () => {
		await Promise.all(fetchers.splice(0).map(async fetcher => fetcher.close()));
	});

	async function start(options?: Parameters<typeof createFetcher>[0]) {
		const created = createFetcher(options);
		fetchers.push(created.fetcher);
		await created.fetcher.start();
		return created;
	}

	it('새 generation에 배정된 파티션은 seek 전까지 fetch하지 않는다', async () => {
		const {consumer, fetcher, handled} = await start();
		consumer.logs.set(0, ['a', 'b', 'c']);
		consumer.assign([0]);
		fetcher.resume([0]);
		await delay(50);
		expect(consumer.fetchOffsets).toEqual([]);

		fetcher.seek(0, {topicId, offset: 1n});
		fetcher.resume([0]);
		await waitFor(() => handled.length === 2);
		expect(handled).toEqual(['b', 'c']);
	});

	it('pause하면 처리 중인 record 다음부터 멈추고, resume하면 다음 offset부터 이어 처리한다', async () => {
		const {promise: blocked, resolve: release} = Promise.withResolvers<void>();
		let isHandlingA = false;
		const {consumer, fetcher, handled} = await start({
			async handle(value) {
				if (value !== 'a') {
					return;
				}

				isHandlingA = true;
				await blocked;
			},
		});
		consumer.logs.set(0, ['a', 'b', 'c']);
		consumer.assign([0]);
		fetcher.seek(0, {topicId, offset: 0n});
		fetcher.resume([0]);
		await waitFor(() => isHandlingA);
		fetcher.pause([0]);
		release();
		await delay(50);
		expect(handled).toEqual(['a']);

		fetcher.resume([0]);
		await waitFor(() => handled.length === 3);
		expect(handled).toEqual(['a', 'b', 'c']);
	});

	it('처리에 실패한 record는 건너뛰지 않고 같은 record부터 다시 처리한다', async () => {
		const attempts: string[] = [];
		let hasFailed = false;
		const {consumer, fetcher, handled} = await start({
			async handle(value) {
				attempts.push(value);
				if (value !== 'b' || hasFailed) {
					return;
				}

				hasFailed = true;
				throw new Error('storage unavailable');
			},
		});
		consumer.logs.set(0, ['a', 'b', 'c']);
		consumer.assign([0]);
		fetcher.seek(0, {topicId, offset: 0n});
		fetcher.resume([0]);
		await waitFor(() => handled.length === 3);
		expect(attempts).toEqual(['a', 'b', 'b', 'c']);
		expect(handled).toEqual(['a', 'b', 'c']);
	});

	it('rebalance 뒤에는 새 generation에서 위치를 다시 받을 때까지 처리하지 않는다', async () => {
		const {consumer, fetcher, handled} = await start();
		consumer.logs.set(0, ['a']);
		consumer.assign([0]);
		fetcher.seek(0, {topicId, offset: 0n});
		fetcher.resume([0]);
		await waitFor(() => handled.length === 1);

		consumer.emit('consumer:group:rebalance', {groupId: 'group'});
		consumer.logs.set(0, ['a', 'b']);
		await delay(50);
		expect(handled).toEqual(['a']);

		consumer.assign([0], 2);
		fetcher.resume([0]);
		await delay(50);
		expect(handled).toEqual(['a']);

		fetcher.seek(0, {topicId, offset: 1n});
		fetcher.resume([0]);
		await waitFor(() => handled.length === 2);
		expect(handled).toEqual(['a', 'b']);
	});

	it('보존 범위를 벗어난 위치는 resetTo가 없으면 건너뛰지 않는다', async () => {
		const {consumer, fetcher, handled} = await start();
		consumer.logs.set(0, ['a', 'b', 'c']);
		consumer.logStart.set(0, 2);
		consumer.assign([0]);
		fetcher.seek(0, {topicId, offset: 0n});
		fetcher.resume([0]);
		await waitFor(() => consumer.fetchOffsets.length > 0);
		await delay(50);
		expect(handled).toEqual([]);
		expect(consumer.fetchOffsets.every(([, offset]) => offset === 0n)).toBe(true);
	});

	it('커밋 offset 모드는 보존 범위를 벗어난 위치를 resetTo 위치로 옮긴다', async () => {
		const {consumer, fetcher, handled} = await start({resetTo: 'earliest'});
		consumer.logs.set(0, ['a', 'b', 'c']);
		consumer.logStart.set(0, 2);
		consumer.assign([0]);
		fetcher.seek(0, {topicId, offset: 0n});
		fetcher.resume([0]);
		await waitFor(() => handled.length === 1);
		expect(handled).toEqual(['c']);
	});

	it('처리한 record 다음 offset만 진행으로 알리고, 실패한 record는 알리지 않는다', async () => {
		const progress: bigint[] = [];
		const {consumer, fetcher, handled} = await start({
			async handle(value) {
				if (value === 'b') {
					throw new Error('always fails');
				}
			},
			async onProgress(_partition, position) {
				progress.push(position.offset);
			},
		});
		consumer.logs.set(0, ['a', 'b', 'c']);
		consumer.assign([0]);
		fetcher.seek(0, {topicId, offset: 0n});
		fetcher.resume([0]);
		await waitFor(() => progress.length > 0);
		await delay(50);
		expect(handled).toEqual(['a']);
		expect(progress).toEqual([1n]);
	});

	it('파티션 안의 record는 fetch를 여러 번 거쳐도 순서대로 한 번씩 처리한다', async () => {
		const {consumer, fetcher, handled} = await start();
		consumer.logs.set(0, ['p0-a']);
		consumer.logs.set(1, ['p1-a']);
		consumer.assign([0, 1]);
		fetcher.seek(0, {topicId, offset: 0n});
		fetcher.seek(1, {topicId, offset: 0n});
		fetcher.resume([0, 1]);
		await waitFor(() => handled.length === 2);
		consumer.logs.get(0)!.push('p0-b');
		consumer.logs.get(1)!.push('p1-b');
		await waitFor(() => handled.length === 4);
		expect(handled.filter(value => value.startsWith('p0'))).toEqual(['p0-a', 'p0-b']);
		expect(handled.filter(value => value.startsWith('p1'))).toEqual(['p1-a', 'p1-b']);
	});

	it('한 broker의 fetch가 실패해도 다른 broker의 처리가 끝나기 전에 같은 파티션을 다시 처리하지 않는다', async () => {
		const {promise: blocked, resolve: release} = Promise.withResolvers<void>();
		let inFlight = 0;
		let maxInFlight = 0;
		const {consumer, fetcher, handled} = await start({
			concurrency: 2,
			async handle(value, partition) {
				if (partition !== 0) {
					return;
				}

				inFlight++;
				maxInFlight = Math.max(maxInFlight, inFlight);
				if (value === 'a') {
					await blocked;
				}

				inFlight--;
			},
		});
		consumer.leaders = [1, 2];
		consumer.failingNode = 2;
		consumer.logs.set(0, ['a', 'b']);
		consumer.assign([0, 1]);
		fetcher.seek(0, {topicId, offset: 0n});
		fetcher.seek(1, {topicId, offset: 0n});
		fetcher.resume([0, 1]);
		await delay(1500);
		release();
		await waitFor(() => handled.length === 2);
		expect(maxInFlight).toBe(1);
		expect(handled).toEqual(['a', 'b']);
	});
});

describe('createCommittedFetcher', () => {
	const fetchers: PartitionFetcher[] = [];
	afterEach(async () => {
		await Promise.all(fetchers.splice(0).map(async fetcher => fetcher.close()));
	});

	async function start(isFromBeginning: boolean) {
		const consumer = new FakeConsumer();
		const handled: string[] = [];
		const fetcher = createCommittedFetcher(consumer as unknown as Consumer, {
			logger,
			topic,
			fromBeginning: isFromBeginning,
			async handle(record) {
				handled.push(record.value!);
			},
		});
		fetchers.push(fetcher);
		await fetcher.start();
		return {consumer, handled};
	}

	it('배정되면 group에 커밋된 offset부터 처리하고, 처리한 다음 offset을 커밋한다', async () => {
		const {consumer, handled} = await start(true);
		consumer.logs.set(0, ['a', 'b', 'c']);
		consumer.committed.set(0, 1n);
		consumer.assign([0]);
		await waitFor(() => handled.length === 2);
		expect(handled).toEqual(['b', 'c']);
		await waitFor(() => consumer.commits.at(-1) === 3n);
	});

	it('커밋된 offset이 없으면 fromBeginning에 따른 위치부터 처리한다', async () => {
		const {consumer, handled} = await start(false);
		consumer.logs.set(0, ['a', 'b']);
		consumer.assign([0]);
		await waitFor(() => consumer.fetchOffsets.length > 0);
		consumer.logs.get(0)!.push('c');
		await waitFor(() => handled.length === 1);
		expect(handled).toEqual(['c']);
	});

	it('조회하는 사이 generation이 바뀌면 이전 조회 결과로 시작하지 않는다', async () => {
		const {consumer, handled} = await start(true);
		const stale = Promise.withResolvers<void>();
		const current = Promise.withResolvers<void>();
		consumer.lookupGates.push(stale.promise, current.promise);
		consumer.logs.set(0, ['a', 'b', 'c']);
		consumer.assign([0], 1);
		consumer.committed.set(0, 2n);
		consumer.assign([0], 2);
		stale.resolve();
		await delay(50);
		expect(consumer.fetchOffsets).toEqual([]);

		current.resolve();
		await waitFor(() => handled.length === 1);
		expect(handled).toEqual(['c']);
	});
});
