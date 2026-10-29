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
import {mongo} from 'mongoose';
import {DicoshotService} from 'dicoshot-nest';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {buildErrorFields} from '../../common/util/discord-alert.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {applyProjectionMessage, ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {TeamMemberProjectionRepository, ChannelProjectionRepository, TeamRoleProjectionRepository} from '../repository/index.js';
import {ChannelMessageReadAccessService} from '../service/index.js';
import {connectWithRetry} from '../../common/kafka/connect-with-retry.js';
import {type Nullable} from '../../common/type/nullable.js';

type TeamMemberEvent = {
	eventType: 'UPSERT' | 'DELETE';
	teamId: number;
	userId: number;
	role: string;
	teamName: string;
	nickname?: Nullable<string>;
	occurredAt: string;
	// @todo #596: 2026-11-01 05:00 KST 이후 구형 이벤트·Mongo auth stamp 보정과 rollback 종료 확인 후 nullable fallback 제거.
	// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
	authorizationVersion?: Nullable<string>;
	snapshot?: boolean;
};

@injectable()
export class TeamMemberEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(TeamMemberEventConsumer.name);
	private consumer!: Consumer;
	private io?: Server;

	@inject()
	private readonly configService!: ConfigService;

	@inject()
	private readonly dicoshot!: DicoshotService;

	@inject()
	private readonly memberRepository!: TeamMemberProjectionRepository;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	@inject()
	private readonly channelRepository!: ChannelProjectionRepository;

	@inject()
	private readonly channelMessageReadAccess!: ChannelMessageReadAccessService;

	@inject()
	private readonly roleRepository!: TeamRoleProjectionRepository;

	private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
		if (!this.isTeamMemberEvent(payload)) {
			throw new ProjectionContractError('invalid team member event payload');
		}

		const expectedKey = `${payload.teamId}:${payload.userId}`;
		if (messageKey !== expectedKey) {
			throw new ProjectionContractError(
				`team member event key mismatch [key=${messageKey ?? '<missing>'}, expected=${expectedKey}]`,
			);
		}

		const eventTime = parseEventTime(payload.occurredAt);
		if (!eventTime) {
			throw new ProjectionContractError('team member event occurredAt must be RFC3339');
		}

		const {occurredAt, sourceVersion} = eventTime;
		const authorizationVersion = parseEventTime(payload.authorizationVersion)?.sourceVersion ?? null;
		if (payload.eventType === 'DELETE') {
			await this.memberRepository.remove({
				teamId: payload.teamId, userId: payload.userId, occurredAt, sourceVersion, authorizationVersion, role: payload.role,
			});
			await this.roleRepository.deleteMemberAssignments(payload.teamId, payload.userId, occurredAt, sourceVersion);
		} else {
			await this.memberRepository.upsert({
				teamId: payload.teamId,
				userId: payload.userId,
				role: payload.role,
				teamName: payload.teamName,
				nickname: payload.nickname ?? null,
				occurredAt,
				sourceVersion,
				authorizationVersion,
			});
		}

		// Replay로 다시 적용되는 과거 레코드는 소켓 이벤트나 접근 취소를 만들지 않는다.
		if (!this.io || !this.projectionReadiness.isStreamLive(PROJECTION_STREAMS.teamMember.name)) {
			return;
		}

		const channelIds = await this.channelRepository.findIdsByTeamId(payload.teamId);
		await this.channelMessageReadAccess.evictUnauthorizedSockets(this.io, channelIds, [payload.userId]);
		if (payload.eventType !== 'DELETE' || (await this.memberRepository.exists(payload.teamId, payload.userId))) {
			return;
		}

		this.io.in(`user:${payload.userId}`).socketsLeave(`team:${payload.teamId}`);
		if (payload.snapshot !== true) {
			this.io.to(`user:${payload.userId}`).emit('team:access:revoked', {teamId: payload.teamId});
		}
	}

	private isTeamMemberEvent(payload: unknown): payload is TeamMemberEvent {
		if (typeof payload !== 'object' || payload === null) {
			return false;
		}

		const event = payload as Partial<TeamMemberEvent>;
		if ((event.eventType !== 'UPSERT' && event.eventType !== 'DELETE')
			|| !isSafePositiveInteger(event.teamId)
			|| !isSafePositiveInteger(event.userId)
			|| (event.snapshot !== undefined && typeof event.snapshot !== 'boolean')
			|| parseEventTime(event.occurredAt) === null || !hasValidAuthorizationVersion(event)) {
			return false;
		}

		if (event.eventType === 'DELETE') {
			return true;
		}

		return typeof event.role === 'string' && event.role.length > 0
			&& typeof event.teamName === 'string' && event.teamName.length > 0
			&& (event.nickname === undefined || event.nickname === null || typeof event.nickname === 'string');
	}

	setSocketServer(io: Server): void {
		this.io = io;
	}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-chat-team-member-event',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.teamMember;
		this.consumer = kafka.consumer({groupId: stream.groupId});
		await connectWithRetry(this.logger, `Kafka consumer startup: ${stream.topic}`, async () => {
			await this.consumer.connect();
			await this.consumer.subscribe({topic: stream.topic, fromBeginning: true});
			await this.projectionReadiness.registerProjection(kafka, this.consumer, stream);
		});

		void this.consumer.run({
			eachMessage: async ({partition, message}): Promise<void> => {
				await this.projectionReadiness.processMessage(stream, partition, message.offset, async () => applyProjectionMessage(stream, {
					partition, message, readiness: this.projectionReadiness, apply: async (payload, key) => this.handleEvent(payload, key),
				},
				));
			},
		}).catch(async (error: unknown) => {
			this.logger.error('team.member.event Kafka consumer failed', error);
			await this.dicoshot.sendCustom({
				title: '🔴 Kafka Consumer 중단',
				description: 'cowork-chat의 team.member.event consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
				color: 'danger',
				fields: [{name: 'Topic', value: 'team.member.event', inline: true}, ...buildErrorFields(error)],
			}).catch(() => {/* Alert delivery is best-effort. */});
			// 처리되지 않은 rejection으로 남겨 프로세스를 종료하고 재시작을 맡긴다.
			throw error;
		});
		this.logger.log('Kafka projection consumer started: team.member.event');
	}

	async onModuleDestroy() {
		await this.consumer.disconnect();
	}
}

/**
`authorizationVersion`이 있으면 0보다 크고 이벤트 발생 시각 이하의 유효한 시각이어야 한다.
*/
function hasValidAuthorizationVersion(event: Partial<TeamMemberEvent>): boolean {
	if (event.authorizationVersion === undefined || event.authorizationVersion === null) {
		return true;
	}

	const authorizationVersion = parseEventTime(event.authorizationVersion)?.sourceVersion;
	const {sourceVersion} = parseEventTime(event.occurredAt)!;
	return authorizationVersion !== undefined
		&& !authorizationVersion.lessThanOrEqual(mongo.Long.ZERO)
		&& !authorizationVersion.greaterThan(sourceVersion);
}
