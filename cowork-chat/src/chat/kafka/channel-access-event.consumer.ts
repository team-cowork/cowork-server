import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
	Inject as inject,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {mongo} from 'mongoose';
import {Server} from 'socket.io';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {ChannelAccessProjectionRepository, type ChannelAccessDecision} from '../repository/index.js';
import {ChannelMessageReadAccessService} from '../service/index.js';
import {startProjectionConsumer} from '../../common/kafka/projection-consumer.js';
import {type Nullable} from '../../common/type/nullable.js';

/**
Preference가 발행하는 채널 권한 키. 레코드는 이 키를 모두 담는다.
*/
export const CHANNEL_SCOPED_PERMISSIONS = [
	'message_read',
	'message_send',
	'thread_reply',
	'file_attach',
	'reaction_add',
	'message_pin',
	'message_manage',
	'slash_command_use',
	'github_issue_create',
	'channel_manage',
	'channel_reorder',
	'slowmode_bypass',
	'voice_connect',
	'voice_speak',
	'screen_share',
	'live_broadcast',
	'voice_log_read',
] as const;

type ChannelAccessEvent = {
	eventType: 'UPSERT';
	channelId: number;
	accountId: number;
	teamId: number;
	permissions: Record<string, unknown>;
	readPrivate: boolean;
	sourceMembershipVersion: string;
	sourceAuthorizationVersion: Nullable<string>;
	occurredAt: string;
	snapshot?: boolean;
};

type ValidChannelAccessEvent = Omit<ChannelAccessEvent, 'sourceAuthorizationVersion'> & {
	sourceAuthorizationVersion: Nullable<string>;
};

function hasValidShape(event: Partial<ChannelAccessEvent>, messageKey?: string): event is ValidChannelAccessEvent {
	return event.eventType === 'UPSERT'
		&& isSafePositiveInteger(event.channelId)
		&& isSafePositiveInteger(event.accountId)
		&& isSafePositiveInteger(event.teamId)
		&& messageKey === `${event.channelId}:${event.accountId}`
		&& typeof event.readPrivate === 'boolean'
		&& typeof event.permissions === 'object' && event.permissions !== null
		&& CHANNEL_SCOPED_PERMISSIONS.every(key => typeof event.permissions![key] === 'boolean')
		&& event.sourceAuthorizationVersion !== undefined
		&& (event.snapshot === undefined || typeof event.snapshot === 'boolean');
}

/**
인가 version이 없는 기록은 모든 권한을 거부해야 한다. 인가 version은 0보다 크고 멤버십 version 이하다.
계약을 어기면 undefined를 반환한다.
*/
function parseAuthorizationVersion(
	value: Nullable<string>,
	membershipVersion: mongo.Long,
	hasAnyGrant: boolean,
): Nullable<mongo.Long> | undefined {
	if (value === null) {
		return hasAnyGrant ? undefined : null;
	}

	const authorization = parseEventTime(value)?.sourceVersion;
	return authorization && authorization.greaterThan(mongo.Long.ZERO) && !authorization.greaterThan(membershipVersion)
		? authorization
		: undefined;
}

/**
`preference.channel-access.state.v1` 레코드를 projection 입력으로 바꾼다. 계약을 어기면 null을 반환한다.
활성 멤버가 아닌 쌍은 `sourceMembershipVersion`이 Unix epoch이고 `sourceAuthorizationVersion`이 null이며 모든 권한이 거부다.
*/
export function parseChannelAccessEvent(payload: unknown, messageKey?: string): Nullable<ChannelAccessDecision> {
	if (typeof payload !== 'object' || payload === null || !hasValidShape(payload, messageKey)) {
		return null;
	}

	const event = payload;
	const occurredAt = parseEventTime(event.occurredAt);
	const membership = parseEventTime(event.sourceMembershipVersion);
	if (!occurredAt || !membership || membership.sourceVersion.lessThan(mongo.Long.ZERO)) {
		return null;
	}

	const allowedPermissions = CHANNEL_SCOPED_PERMISSIONS.filter(key => event.permissions[key] === true);
	const sourceAuthorizationVersion = parseAuthorizationVersion(
		event.sourceAuthorizationVersion,
		membership.sourceVersion,
		allowedPermissions.length > 0 || event.readPrivate,
	);
	if (sourceAuthorizationVersion === undefined) {
		return null;
	}

	return {
		channelId: event.channelId,
		userId: event.accountId,
		teamId: event.teamId,
		allowedPermissions,
		readPrivate: event.readPrivate,
		occurredAt: occurredAt.occurredAt,
		sourceVersion: occurredAt.sourceVersion,
		sourceMembershipVersion: membership.sourceVersion,
		sourceAuthorizationVersion,
	};
}

@injectable()
export class ChannelAccessEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(ChannelAccessEventConsumer.name);
	private consumer!: Consumer;
	private io?: Server;

	@inject()
	private readonly configService!: ConfigService;

	@inject()
	private readonly repository!: ChannelAccessProjectionRepository;

	@inject()
	private readonly accessService!: ChannelMessageReadAccessService;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
		const decision = parseChannelAccessEvent(payload, messageKey);
		if (!decision) {
			throw new ProjectionContractError(`invalid channel access event [key=${messageKey ?? '<missing>'}]`);
		}

		const isApplied = await this.repository.upsert(decision);
		// Replay로 다시 적용되는 과거 레코드는 접근 취소를 만들지 않는다.
		if (!this.io || !this.projectionReadiness.isStreamLive(PROJECTION_STREAMS.channelAccess.name)) {
			return;
		}

		// 변경 없이 끝난 snapshot 재발행은 소켓 재검증을 건너뛰고, live 재전달은 항상 재검증한다.
		if (isApplied || (payload as {snapshot?: boolean}).snapshot !== true) {
			await this.accessService.evictUnauthorizedSockets(this.io, [decision.channelId], [decision.userId]);
		}
	}

	setSocketServer(io: Server): void {
		this.io = io;
	}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-chat-channel-access-state',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.channelAccess;
		this.consumer = kafka.consumer({groupId: stream.groupId});
		await startProjectionConsumer({
			logger: this.logger, kafka, consumer: this.consumer, readiness: this.projectionReadiness, stream,
			handleEvent: async (payload, key) => this.handleEvent(payload, key),
		});
	}

	async onModuleDestroy(): Promise<void> {
		await this.consumer.disconnect();
	}
}
