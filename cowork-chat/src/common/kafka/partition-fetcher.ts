import {setTimeout as delay} from 'node:timers/promises';
import {type Logger} from '@nestjs/common';
import {type ConfigService} from '@nestjs/config';
import {
	Consumer,
	type ConsumerGroupJoinPayload,
	findErrorBy,
	IS_CONTROL,
	type KafkaRecord,
	ListOffsetTimestamps,
	protocolErrors,
	type RecordsBatch,
	ResponseError,
} from '@platformatic/kafka';
import {getKafkaClientConfig} from '../config/config.util.js';
import {type Nullable} from '../type/nullable.js';
import {connectWithRetry} from './connect-with-retry.js';

const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 30_000;
const PARTITION_MAX_BYTES = 1_048_576;
/**
 한 라운드는 모든 broker의 fetch를 기다리므로, 한가한 broker의 long poll(기본 5초)이 다른 broker 파티션을 막지 않게 짧게 둔다.
 */
const FETCH_MAX_WAIT_MS = 500;
const OFFSET_OUT_OF_RANGE = protocolErrors.OFFSET_OUT_OF_RANGE.code;

type FetchResponse = Awaited<ReturnType<Consumer['fetch']>>;

/**
Fetch한 record 하나. offset은 JavaScript 안전 정수 범위를 넘을 수 있어 10진 문자열로 두고, key와 value는 UTF-8로 읽는다.
*/
export type ConsumedRecord = {
	topic: string;
	partition: number;
	offset: string;
	key: Nullable<string>;
	value: Nullable<string>;
};

export type KafkaConsumerHandle = {
	close(): Promise<void>;
};

/**
 다음에 처리할 offset과 그 offset이 속한 topic UUID. 같은 이름으로 다시 만든 topic은 UUID가 달라
 이 위치로 이어 읽지 않는다. seek은 새 객체를 만들고, 처리는 같은 객체의 offset만 전진시킨다.
 */
export type FetchPosition = {
	readonly topicId: string;
	offset: bigint;
};

/**
 Group generation에 배정된 파티션 하나의 상태.
 - claiming: 이번 generation에 배정됐지만 시작 위치가 아직 없다(Mongo lease claim 또는 커밋 offset 조회 중).
 - idle: 위치가 있지만 fetch하지 않는다.
 - fetching: 위치부터 fetch해 record를 순서대로 처리한다.
 배정에서 빠진 파티션은 map에 없다.
 */
type PartitionLoop =
	| {readonly status: 'claiming'}
	| {readonly status: 'idle'; readonly position: FetchPosition}
	| {readonly status: 'fetching'; readonly position: FetchPosition};

type FetcherOptions = {
	logger: Logger;
	consumer: Consumer;
	topic: string;
	concurrency: number;
	handle(record: ConsumedRecord): Promise<void>;
	/**
	 커밋 offset 모드에서만 둔다. 없으면 보존 범위를 벗어난 위치로 건너뛰지 않고 같은 위치를 다시 시도한다.
	 */
	resetTo?: 'earliest' | 'latest';
	/**
	 한 번의 fetch 결과를 처리한 뒤 전진한 위치를 알린다. 파티션이 아직 같은 위치를 소유할 때만 호출한다.
	 */
	onProgress?(partition: number, position: FetchPosition): Promise<void>;
};

/**
 Consumer group 하나의 파티션별 fetch loop. Group membership과 heartbeat는 platformatic Consumer가
 독립 타이머로 유지하고, 여기서는 어느 파티션을 어디서부터 읽을지만 정한다.

 새 generation에 배정되면 모든 파티션은 `claiming`에서 시작해, 호출자가 `seek`과 `resume`을 해야만 fetch한다.
 한 파티션의 record는 항상 하나씩 순서대로 처리하고, 처리 중 pause/seek/재배정이 일어나면 다음 record부터 멈춘다.
 */
export class PartitionFetcher implements KafkaConsumerHandle {
	private readonly loops = new Map<number, PartitionLoop>();
	private stopped = false;
	private wake?: () => void;
	private readonly permits: Permits;
	readonly consumer: Consumer;

	constructor(private readonly options: FetcherOptions) {
		this.consumer = options.consumer;
		this.permits = new Permits(options.concurrency);
		const {consumer, topic, logger} = options;
		consumer.on('consumer:group:join', (payload: ConsumerGroupJoinPayload) => {
			this.loops.clear();
			for (const partition of payload.assignments?.find(assignment => assignment.topic === topic)?.partitions ?? []) {
				this.loops.set(partition, {status: 'claiming'});
			}
		});
		for (const event of ['consumer:group:rebalance', 'consumer:group:leave'] as const) {
			consumer.on(event, () => {
				this.loops.clear();
			});
		}

		consumer.on('consumer:group:lost', ({error}) => {
			this.loops.clear();
			if (this.stopped) {
				return;
			}

			logger.warn(`Kafka consumer lost its group membership, rejoining [topic=${topic}]: ${describe(error)}`);
			void connectWithRetry(logger, `Kafka consumer rejoin: ${topic}`, async () => consumer.joinGroup({})).catch((joinError: unknown) => {
				logger.error(`${topic} Kafka consumer could not rejoin its group`, joinError);
				// 처리되지 않은 rejection으로 남겨 프로세스를 종료하고 재시작을 맡긴다.
				throw joinError;
			});
		});
		// 재참여 실패는 'error'와 'consumer:group:lost'로 함께 알려진다. listener가 없으면 프로세스가 바로 죽는다.
		consumer.on('error', (error: Error) => {
			logger.warn(`Kafka consumer error [topic=${topic}]: ${describe(error)}`);
		});
	}

	private isCurrent(partition: number, position: FetchPosition): boolean {
		const loop = this.loops.get(partition);
		return !this.stopped && loop?.status === 'fetching' && loop.position === position;
	}

	/**
	 다음 round를 기다리지 않고 예약해, 끝없는 loop가 promise chain을 쌓지 않게 한다.
	 */
	private async runRounds(backoffMs: number): Promise<void> {
		if (this.stopped) {
			return;
		}

		const hasFailed = await this.round();
		if (hasFailed) {
			await this.sleep(backoffMs);
		}

		void this.runRounds(hasFailed ? Math.min(backoffMs * 2, MAX_BACKOFF_MS) : INITIAL_BACKOFF_MS);
	}

	/**
	 Fetch할 파티션이 없으면 resume을 기다린다. 실패하면 `true`를 반환한다.
	 */
	private async round(): Promise<boolean> {
		const positions = new Map([...this.loops].flatMap(([partition, loop]) => (
			loop.status === 'fetching' ? [[partition, loop.position] as const] : []
		)));
		if (positions.size === 0) {
			await this.waitForWake();
			return false;
		}

		try {
			return await this.fetchRound(positions);
		} catch (error) {
			if (!this.stopped) {
				this.options.logger.warn(`Kafka fetch failed [topic=${this.options.topic}]: ${describe(error)}`);
			}

			return true;
		}
	}

	private async waitForWake(): Promise<void> {
		await new Promise<void>(resolve => {
			this.wake = resolve;
		});
		this.wake = undefined;
	}

	/**
	 Resume과 close가 backoff를 끝까지 기다리지 않게 wake로도 깨운다.
	 */
	private async sleep(ms: number): Promise<void> {
		await Promise.race([delay(ms, undefined, {ref: false}), this.waitForWake()]);
		this.wake = undefined;
	}

	/**
	 파티션을 leader broker별로 묶어 broker마다 fetch 하나만 보낸다. Platformatic은 broker마다 fetch session
	 하나를 유지하므로, 같은 broker에 fetch를 동시에 둘 보내면 session epoch가 어긋난다.
	 */
	private async fetchRound(positions: Map<number, FetchPosition>): Promise<boolean> {
		const metadata = await this.consumer.metadata({topics: [this.options.topic]});
		const partitionsMetadata = metadata.topics.get(this.options.topic)?.partitions ?? [];
		const byNode = new Map<number, Map<number, FetchPosition>>();
		let hasFailed = false;
		for (const [partition, position] of positions) {
			const leader = partitionsMetadata[partition]?.leader;
			if (leader === undefined || leader < 0) {
				this.options.logger.warn(`Kafka partition has no leader [topic=${this.options.topic}, partition=${partition}]`);
				hasFailed = true;
				continue;
			}

			const nodePositions = byNode.get(leader) ?? new Map<number, FetchPosition>();
			nodePositions.set(partition, position);
			byNode.set(leader, nodePositions);
		}

		const results = await Promise.allSettled([...byNode].map(async ([node, nodePositions]) => this.fetchFromNode(node, nodePositions)));
		return this.anyFailed(results) || hasFailed;
	}

	private async fetchFromNode(node: number, positions: Map<number, FetchPosition>): Promise<boolean> {
		const byTopicId = new Map<string, number[]>();
		for (const [partition, {topicId}] of positions) {
			byTopicId.set(topicId, [...(byTopicId.get(topicId) ?? []), partition]);
		}

		const response = await this.fetch(node, [...byTopicId].map(([topicId, partitions]) => ({
			topicId,
			partitions: partitions.map(partition => ({
				partition,
				fetchOffset: positions.get(partition)!.offset,
				currentLeaderEpoch: -1,
				lastFetchedEpoch: -1,
				partitionMaxBytes: PARTITION_MAX_BYTES,
			})),
		})));
		const results = await Promise.allSettled(response.responses.flatMap(topicResponse => topicResponse.partitions.map(async partitionResponse => {
			const partition = partitionResponse.partitionIndex;
			const position = positions.get(partition);
			// Incremental fetch session은 요청하지 않은 파티션을 돌려줄 수 있다.
			if (position?.topicId !== topicResponse.topicId) {
				return false;
			}

			if (partitionResponse.errorCode === OFFSET_OUT_OF_RANGE) {
				return this.handleOffsetOutOfRange(partition, position);
			}

			if (partitionResponse.errorCode !== 0) {
				this.options.logger.warn(
					`Kafka fetch partition error [topic=${this.options.topic}, partition=${partition}, offset=${position.offset}, `
					+ `errorCode=${partitionResponse.errorCode}]`,
				);
				return true;
			}

			return this.permits.run(async () => this.process(partition, position, partitionResponse.records ?? []));
		})));
		return this.anyFailed(results);
	}

	/**
	 `Promise.all`은 한 갈래가 실패하면 나머지 처리가 끝나기 전에 라운드를 끝내, 다음 라운드가 같은 파티션을 겹쳐 처리하게 된다.
	 그래서 모든 갈래가 끝날 때까지 기다리고, 실패한 갈래는 로그를 남긴 뒤 재시도 대상으로 본다.
	 */
	private anyFailed(results: Array<PromiseSettledResult<boolean>>): boolean {
		let hasFailed = false;
		for (const result of results) {
			if (result.status === 'rejected') {
				this.options.logger.warn(`Kafka fetch failed [topic=${this.options.topic}]: ${String(result.reason)}`);
				hasFailed = true;
			} else if (result.value) {
				hasFailed = true;
			}
		}

		return hasFailed;
	}

	/**
	 파티션 오류가 있어도 platformatic은 전체 응답을 오류에 담아 준다. 정상 파티션의 record는 그대로 처리한다.
	 */
	private async fetch(node: number, topics: Parameters<Consumer['fetch']>[0]['topics']): Promise<FetchResponse> {
		try {
			return await this.consumer.fetch({node, topics, maxWaitTime: FETCH_MAX_WAIT_MS});
		} catch (error) {
			const failed = findErrorBy<ResponseError & {response?: FetchResponse}>(error as Error, 'code', ResponseError.code);
			if (!failed?.response?.responses) {
				throw error;
			}

			return failed.response;
		}
	}

	/**
	 처리에 실패하면 `true`를 반환한다. 실패한 record는 위치를 넘기지 않아 다음 fetch에서 다시 읽는다.
	 */
	private async process(partition: number, position: FetchPosition, batches: RecordsBatch[]): Promise<boolean> {
		const startOffset = position.offset;
		const records = batches.flatMap(batch => (
			// Control batch(transaction marker)는 처리하지 않고 위치만 넘긴다.
			isControlBatch(batch)
				? []
				: batch.records.map(record => ({...record, offset: batch.firstOffset + BigInt(record.offsetDelta)}))
		)).filter(record => record.offset >= startOffset);
		const hasFailed = await this.processRecords(partition, position, records, 0);
		const lastBatch = batches.at(-1);
		const batchEnd = lastBatch ? lastBatch.firstOffset + BigInt(lastBatch.lastOffsetDelta) + 1n : startOffset;
		if (!hasFailed && batchEnd > position.offset && this.isCurrent(partition, position)) {
			position.offset = batchEnd;
		}

		const loop = this.loops.get(partition);
		if (loop !== undefined && loop.status !== 'claiming' && position.offset > startOffset && loop.position === position) {
			await this.options.onProgress?.(partition, position).catch((error: unknown) => {
				this.options.logger.warn(`Kafka offset commit failed [topic=${this.options.topic}, partition=${partition}]: ${describe(error)}`);
			});
		}

		return hasFailed;
	}

	private async processRecords(
		partition: number,
		position: FetchPosition,
		records: Array<KafkaRecord & {offset: bigint}>,
		index: number,
	): Promise<boolean> {
		if (index >= records.length || !this.isCurrent(partition, position)) {
			return false;
		}

		const record = records[index];
		try {
			await this.options.handle({
				topic: this.options.topic,
				partition,
				offset: record.offset.toString(),
				key: record.key?.toString() ?? null,
				value: record.value?.toString() ?? null,
			});
		} catch (error) {
			this.options.logger.error(
				`Kafka record processing failed, retrying [topic=${this.options.topic}, partition=${partition}, offset=${record.offset}]`,
				error,
			);
			return true;
		}

		position.offset = record.offset + 1n;
		return this.processRecords(partition, position, records, index + 1);
	}

	private async handleOffsetOutOfRange(partition: number, position: FetchPosition): Promise<boolean> {
		const {resetTo, topic, logger} = this.options;
		if (resetTo === undefined) {
			logger.error(`Kafka fetch offset is outside the retained log; not skipping [topic=${topic}, partition=${partition}, offset=${position.offset}]`);
			return true;
		}

		const offsets = await this.consumer.listOffsets({
			topics: [topic],
			partitions: {[topic]: [partition]},
			timestamp: resetTo === 'earliest' ? ListOffsetTimestamps.EARLIEST : ListOffsetTimestamps.LATEST,
		});
		const offset = offsets.get(topic)?.[partition];
		if (offset === undefined || !this.isCurrent(partition, position)) {
			return true;
		}

		logger.warn(`Kafka offset out of range, resetting to ${resetTo} [topic=${topic}, partition=${partition}, from=${position.offset}, to=${offset}]`);
		this.seek(partition, {topicId: position.topicId, offset});
		return false;
	}

	/**
	 Topic을 구독하고 group에 참여한 뒤 fetch를 시작한다. 한 번만 호출한다.
	 */
	async start(): Promise<void> {
		this.consumer.topics.track(this.options.topic);
		await this.consumer.joinGroup({});
		void this.runRounds(INITIAL_BACKOFF_MS);
	}

	/**
	 처리 중인 record는 기다리지 않는다. 끝나지 않은 record는 커밋되지 않아 다음 owner가 다시 처리한다.
	 */
	async close(): Promise<void> {
		this.stopped = true;
		this.loops.clear();
		this.wake?.();
		await this.consumer.close();
	}

	pause(partitions: number[]): void {
		for (const partition of partitions) {
			const loop = this.loops.get(partition);
			if (loop?.status === 'fetching') {
				this.loops.set(partition, {status: 'idle', position: loop.position});
			}
		}
	}

	resume(partitions: number[]): void {
		for (const partition of partitions) {
			const loop = this.loops.get(partition);
			if (loop?.status === 'idle') {
				this.loops.set(partition, {status: 'fetching', position: loop.position});
			}
		}

		this.wake?.();
	}

	/**
	 현재 generation에 배정된 파티션의 다음 위치를 바꾼다. `fetching`/`idle`은 유지하고 `claiming`은 `idle`이 된다.
	 */
	seek(partition: number, position: {topicId: string; offset: bigint}): void {
		const loop = this.loops.get(partition);
		if (!loop) {
			return;
		}

		this.loops.set(partition, {
			status: loop.status === 'fetching' ? 'fetching' : 'idle',
			position: {topicId: position.topicId, offset: position.offset},
		});
	}

	assignedPartitions(): number[] {
		return this.loops.keys().toArray();
	}
}

/**
 Batch attributes의 5번 bit가 control batch(transaction marker)를 뜻한다.
 */
function isControlBatch(batch: RecordsBatch): boolean {
	return Math.trunc(batch.attributes / IS_CONTROL) % 2 === 1;
}

/**
 동시에 실행할 수 있는 파티션 처리 수를 제한한다.
 */
class Permits {
	private readonly waiters: Array<() => void> = [];

	constructor(private available: number) {}

	async run<T>(work: () => Promise<T>): Promise<T> {
		if (this.available > 0) {
			this.available -= 1;
		} else {
			await new Promise<void>(resolve => {
				this.waiters.push(resolve);
			});
		}

		try {
			return await work();
		} finally {
			const next = this.waiters.shift();
			if (next) {
				next();
			} else {
				this.available += 1;
			}
		}
	}
}

/**
 Projection assignment lease(15초)는 heartbeat(3초)보다 충분히 길고 session timeout(30초)보다 짧아야 한다.
 */
export function createGroupConsumer(configService: ConfigService, clientId: string, groupId: string): Consumer {
	return new Consumer({
		clientId,
		groupId,
		...getKafkaClientConfig(configService),
		autocommit: false,
		sessionTimeout: 30_000,
		rebalanceTimeout: 60_000,
		heartbeatInterval: 3000,
	});
}

/**
 Broker group offset을 기준으로 소비한다. `handle`이 끝나면 offset을 커밋하고, 예외를 던지면 커밋하지 않고
 backoff 뒤 같은 record부터 다시 fetch한다. 파티션 안의 순서는 항상 유지한다.
 */
export async function startCommittedConsumer(input: {
	logger: Logger;
	configService: ConfigService;
	clientId: string;
	groupId: string;
	topic: string;
	/**
	 커밋된 offset이 없을 때의 시작 위치이자, 커밋된 offset이 보존 범위를 벗어났을 때 되돌아갈 위치.
	 */
	fromBeginning: boolean;
	/**
	 동시에 `handle`을 실행할 수 있는 파티션 수. 기본값 1.
	 */
	concurrency?: number;
	handle(record: ConsumedRecord): Promise<void>;
}): Promise<KafkaConsumerHandle> {
	const {logger, topic} = input;
	const consumer = createGroupConsumer(input.configService, input.clientId, input.groupId);
	const resetTo = input.fromBeginning ? 'earliest' : 'latest';
	const fetcher = new PartitionFetcher({
		logger,
		consumer,
		topic,
		concurrency: input.concurrency ?? 1,
		handle: input.handle,
		resetTo,
		async onProgress(partition, position) {
			await consumer.commit({
				offsets: [{
					topic, partition, offset: position.offset, leaderEpoch: -1,
				}],
			});
		},
	});
	consumer.on('consumer:group:join', ({generationId}) => {
		void connectWithRetry(
			logger,
			`Kafka committed offset lookup: ${topic}`,
			async () => startFromCommittedOffsets(fetcher, {
				topic, resetTo, generationId,
			}),
		).catch((error: unknown) => {
			logger.error(`${topic} Kafka committed offset lookup failed`, error);
			// 처리되지 않은 rejection으로 남겨 프로세스를 종료하고 재시작을 맡긴다.
			throw error;
		});
	});
	await connectWithRetry(logger, `Kafka consumer startup: ${topic}`, async () => fetcher.start());
	logger.log(`Kafka consumer started: ${topic}`);
	return fetcher;
}

/**
 배정된 파티션을 group에 커밋된 offset부터 시작한다. 커밋이 없으면 `resetTo` 위치부터 시작한다.
 */
async function startFromCommittedOffsets(
	fetcher: PartitionFetcher,
	input: {topic: string; resetTo: 'earliest' | 'latest'; generationId?: number},
): Promise<void> {
	const {consumer} = fetcher;
	const {topic} = input;
	const partitions = fetcher.assignedPartitions();
	if (partitions.length === 0) {
		return;
	}

	const [metadata, committed, fallback] = await Promise.all([
		consumer.metadata({topics: [topic]}),
		consumer.listCommittedOffsets({topics: [{topic, partitions}]}),
		consumer.listOffsets({
			topics: [topic],
			partitions: {[topic]: partitions},
			timestamp: input.resetTo === 'earliest' ? ListOffsetTimestamps.EARLIEST : ListOffsetTimestamps.LATEST,
		}),
	]);
	if (consumer.generationId !== input.generationId) {
		return;
	}

	const topicId = metadata.topics.get(topic)!.id;
	for (const partition of partitions) {
		const offset = committed.get(topic)?.[partition] ?? -1n;
		fetcher.seek(partition, {topicId, offset: offset >= 0n ? offset : fallback.get(topic)![partition]});
	}

	fetcher.resume(partitions);
}

function describe(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}
