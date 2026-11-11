import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
	Inject as inject,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {Server} from 'socket.io';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {parseEventTime, type ParsedEventTime} from '../../common/util/event-time.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {ChannelProjectionEvent, ChannelProjectionRepository} from '../repository/index.js';
import {ChannelMessageReadAccessService} from '../service/index.js';
import {startProjectionConsumer} from '../../common/kafka/projection-consumer.js';
import {type Nullable} from '../../common/type/nullable.js';

type ChannelEvent = {
	eventType: 'CREATED' | 'UPDATED' | 'DELETED';
	channelId: number;
	teamId: Nullable<number>;
	/**
    팀 채널은 null, 프로젝트 채널은 해당 project ID. legacy 이벤트는 누락될 수 있다.
    */
	projectId?: Nullable<number>;
	name: string;
	type: string;
	viewType: string;
	description: Nullable<string>;
	isPrivate: boolean;
	/**
    보관 상태. 필드가 추가되기 전에 발행된 이벤트는 누락될 수 있으며 활성 채널로 본다.
    */
	archived?: boolean;
	occurredAt: string;
	/**
    주기/startup state replay이면 변경 알림 부수효과를 억제한다.
    */
	snapshot?: boolean;
	initialRolePolicySeed?: unknown;
	/**
    이전 producer 이벤트와의 호환을 위해 선택값이며, 누락 시 0으로 저장한다.
    */
	position?: number;
};

@injectable()
export class ChannelEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(ChannelEventConsumer.name);
	private consumer!: Consumer;
	private io?: Server;

	@inject()
	private readonly configService!: ConfigService;

	@inject()
	private readonly channelRepository!: ChannelProjectionRepository;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	@inject()
	private readonly channelMessageReadAccess!: ChannelMessageReadAccessService;

	private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
		if (!this.isChannelEvent(payload)) {
			throw new ProjectionContractError('invalid channel event payload');
		}

		const event = payload;
		if (messageKey !== `${event.channelId}`) {
			throw new ProjectionContractError(
				`channel event key does not match channelId [key=${messageKey ?? '<missing>'}, channelId=${event.channelId}]`,
			);
		}

		const eventTime = parseEventTime(event.occurredAt);
		if (!eventTime) {
			throw new ProjectionContractError('channel event occurredAt must be RFC3339');
		}

		const deletionRecipientSocketIds = event.eventType === 'DELETED'
			&& event.snapshot !== true
			&& event.teamId !== null
			&& this.io
			? await this.channelMessageReadAccess.captureVisibleTeamSocketIds(
				this.io,
				event.teamId,
				event.channelId,
			)
			: [];
		const isApplied = await this.applyChannelEvent(event, eventTime);

		// Replay로 다시 적용되는 과거 레코드는 소켓 이벤트나 접근 취소를 만들지 않는다.
		if (!this.io || !this.projectionReadiness.isStreamLive(PROJECTION_STREAMS.channel.name)) {
			return;
		}

		await this.broadcastChannelEvent(this.io, event, isApplied, deletionRecipientSocketIds);
	}

	private async applyChannelEvent(event: ChannelEvent, {occurredAt, sourceVersion}: ParsedEventTime): Promise<boolean> {
		if (event.eventType === 'DELETED') {
			return this.channelRepository.remove(event.channelId, occurredAt, sourceVersion);
		}

		const projectionEvent: ChannelProjectionEvent = {
			eventType: event.eventType,
			channelId: event.channelId,
			teamId: event.teamId,
			projectId: event.projectId ?? null,
			name: event.name,
			type: event.type,
			viewType: event.viewType,
			description: event.description,
			isPrivate: event.isPrivate,
			position: event.position ?? 0,
			archived: event.archived ?? false,
			occurredAt,
			sourceVersion,
		};
		return this.channelRepository.upsert(projectionEvent);
	}

	private async broadcastChannelEvent(io: Server, event: ChannelEvent, isApplied: boolean, deletionRecipientSocketIds: string[]): Promise<void> {
		// 변경 없이 끝난 snapshot 재발행은 소켓 재검증을 건너뛰고, live 재전달은 항상 재검증한다.
		if (event.eventType === 'UPDATED' && (isApplied || event.snapshot !== true)) {
			await this.channelMessageReadAccess.evictUnauthorizedSockets(io, [event.channelId]);
		}

		if (event.snapshot === true) {
			if (event.eventType === 'DELETED'
				&& (isApplied || await this.channelRepository.findById(event.channelId) === null)) {
				io.in(`chat:${event.channelId}`).socketsLeave(`chat:${event.channelId}`);
			}

			return;
		}

		const {eventType, snapshot, initialRolePolicySeed, ...projectionPayload} = event;
		if (eventType === 'DELETED') {
			if (!isApplied && await this.channelRepository.findById(event.channelId) !== null) {
				return;
			}

			for (const socketId of deletionRecipientSocketIds) {
				io.to(socketId).emit('channel:deleted', {channelId: event.channelId, teamId: event.teamId});
			}

			io.in(`chat:${event.channelId}`).socketsLeave(`chat:${event.channelId}`);
			return;
		}

		if (event.teamId === null) {
			return;
		}

		if (eventType === 'CREATED') {
			if (!isApplied) {
				return;
			}

			await this.channelMessageReadAccess.emitChannelEventToVisibleTeamUsers(io, event.teamId, event.channelId, {event: 'channel:created', payload: projectionPayload});
		} else if (eventType === 'UPDATED') {
			if (!isApplied) {
				return;
			}

			await this.channelMessageReadAccess.emitChannelEventToVisibleTeamUsers(io, event.teamId, event.channelId, {event: 'channel:updated', payload: projectionPayload});
		}
	}

	private isChannelEvent(payload: unknown): payload is ChannelEvent {
		if (typeof payload !== 'object' || payload === null) {
			return false;
		}

		const event = payload as Partial<ChannelEvent>;
		return hasValidChannelEventEnvelope(event) ? event.eventType === 'DELETED' || hasValidChannelDetails(event) : false;
	}

	setSocketServer(io: Server) {
		this.io = io;
	}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-chat-channel-event',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.channel;
		this.consumer = kafka.consumer({groupId: stream.groupId});
		await startProjectionConsumer({
			logger: this.logger, kafka, consumer: this.consumer, readiness: this.projectionReadiness, stream,
			handleEvent: async (payload, key) => this.handleEvent(payload, key),
		});
	}

	async onModuleDestroy() {
		await this.consumer.disconnect();
	}
}

function hasValidChannelEventEnvelope(event: Partial<ChannelEvent>): boolean {
	return ['CREATED', 'UPDATED', 'DELETED'].includes(event.eventType ?? '')
		&& isSafePositiveInteger(event.channelId)
		&& (event.teamId === null || isSafePositiveInteger(event.teamId))
		&& (event.projectId === undefined || event.projectId === null || isSafePositiveInteger(event.projectId))
		&& parseEventTime(event.occurredAt) !== null
		&& (event.snapshot === undefined || typeof event.snapshot === 'boolean');
}

function hasValidChannelDetails(event: Partial<ChannelEvent>): boolean {
	return typeof event.name === 'string'
		&& typeof event.type === 'string'
		&& typeof event.viewType === 'string'
		&& (event.description === null || typeof event.description === 'string')
		&& typeof event.isPrivate === 'boolean'
		&& (event.position === undefined || typeof event.position === 'number')
		&& (event.archived === undefined || typeof event.archived === 'boolean');
}
