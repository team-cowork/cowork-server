import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {Server} from 'socket.io';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {startProjectionConsumer} from '../../common/kafka/projection-consumer.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {type ThreadApplyResult, ThreadProjectionRepository, type ThreadState} from '../repository/thread-projection.repository.js';
import {THREAD_STATUSES, type ThreadStatus} from '../schema/thread-projection.schema.js';
import {ChannelMessageReadAccessService} from '../service/index.js';
import {type Nullable} from '../../common/type/nullable.js';

const OBJECT_ID = /^[0-9a-f]{24}$/v;

export function parseThreadStateEvent(payload: unknown, key?: string): ThreadState {
	const event = payload as Partial<Record<keyof ThreadState, unknown>> | undefined;
	if (!event || !isSafePositiveInteger(event.threadId) || key !== String(event.threadId)
		|| !isSafePositiveInteger(event.channelId) || !isSafePositiveInteger(event.createdBy)
		|| typeof event.name !== 'string' || event.name.length > 100
		|| typeof event.parentMessageId !== 'string' || !OBJECT_ID.test(event.parentMessageId)
		|| typeof event.status !== 'string' || !(THREAD_STATUSES as readonly string[]).includes(event.status)
		|| typeof event.archived !== 'boolean' || typeof event.deleted !== 'boolean') {
		throw new ProjectionContractError('Invalid thread state');
	}

	// 거부된 스레드는 channel에서 지워지므로 삭제 상태로만 온다.
	if (event.status === 'REJECTED' && !event.deleted) {
		throw new ProjectionContractError('Rejected thread state must be deleted');
	}

	const time = parseEventTime(event.occurredAt);
	if (!time) {
		throw new ProjectionContractError('Thread state occurredAt must be RFC3339');
	}

	return {
		threadId: event.threadId,
		channelId: event.channelId,
		name: event.name,
		parentMessageId: event.parentMessageId,
		createdBy: event.createdBy,
		status: event.status as ThreadStatus,
		archived: event.archived,
		deleted: event.deleted,
		...time,
	};
}

export type ThreadSocketEvent = {
	/**
    `channel`이면 채널을 읽을 수 있는 사용자에게, `user`면 스레드를 만든 사용자에게만 보낸다
    */
	target: 'channel' | 'user';
	event: 'thread:created' | 'thread:updated' | 'thread:rejected';
	payload: Record<string, unknown>;
};

/**
 적용한 스레드 상태에서 소켓으로 알릴 이벤트를 정한다.

 - 검증 대기(`PENDING`) 스레드와 채널 삭제로 지워진 스레드는 알리지 않는다.
 - 처음 `ACTIVE`가 된 스레드는 `thread:created`, 이미 `ACTIVE`였던 스레드의 변경은 `thread:updated`로 채널에 알린다.
 - 시작 메시지 검증에서 거부된 스레드는 만든 사용자에게만 `thread:rejected`로 알린다. 채널 구성원은 이 스레드를 본 적이 없다.
 */
export function threadSocketEvent(state: ThreadState, {applied, previous}: ThreadApplyResult): Nullable<ThreadSocketEvent> {
	if (!applied) {
		return null;
	}

	if (state.status === 'REJECTED') {
		return previous?.status === 'REJECTED'
			? null
			: {target: 'user', event: 'thread:rejected', payload: {id: state.threadId, channelId: state.channelId, parentMessageId: state.parentMessageId}};
	}

	if (state.deleted || state.status !== 'ACTIVE') {
		return null;
	}

	const payload = {
		id: state.threadId,
		channelId: state.channelId,
		name: state.name,
		parentMessageId: state.parentMessageId,
		isArchived: state.archived,
		createdBy: state.createdBy,
	};
	const wasActive = previous?.status === 'ACTIVE' && !previous.deleted;
	return {target: 'channel', event: wasActive ? 'thread:updated' : 'thread:created', payload};
}

/**
 `channel.thread.state.v1`을 스레드 projection에 반영하고, 실시간 레코드는 소켓으로 채널에 알린다.
 */
@injectable()
export class ThreadStateConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(ThreadStateConsumer.name);
	private consumer!: Consumer;
	private io?: Server;

	constructor(
		private readonly config: ConfigService,
		private readonly readiness: ProjectionReadinessService,
		private readonly repository: ThreadProjectionRepository,
		private readonly channelMessageReadAccess: ChannelMessageReadAccessService,
	) {}

	private async handleEvent(payload: unknown, key?: string): Promise<void> {
		const state = parseThreadStateEvent(payload, key);
		const result = await this.repository.apply(state);

		// Replay로 다시 적용되는 과거 레코드는 소켓 이벤트를 만들지 않는다.
		if (!this.io || !this.readiness.isStreamLive(PROJECTION_STREAMS.thread.name)) {
			return;
		}

		const socketEvent = threadSocketEvent(state, result);
		if (!socketEvent) {
			return;
		}

		if (socketEvent.target === 'user') {
			this.io.to(`user:${state.createdBy}`).emit(socketEvent.event, socketEvent.payload);
			return;
		}

		await this.channelMessageReadAccess.emitToReadableChannelUsers(this.io, state.channelId, {event: socketEvent.event, payload: socketEvent.payload});
	}

	setSocketServer(io: Server) {
		this.io = io;
	}

	async onModuleInit() {
		const kafka = new Kafka({clientId: 'cowork-chat-thread-state', ...getKafkaClientConfig(this.config)});
		const stream = PROJECTION_STREAMS.thread;
		this.consumer = kafka.consumer({groupId: stream.groupId});
		await startProjectionConsumer({
			logger: this.logger, kafka, consumer: this.consumer, readiness: this.readiness, stream,
			handleEvent: async (payload, key) => this.handleEvent(payload, key),
		});
	}

	async onModuleDestroy() {
		await this.consumer.disconnect();
	}
}
