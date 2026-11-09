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
import {parseEventTime} from '../../common/util/event-time.util.js';
import {isBooleanRecord} from '../../common/util/boolean-record.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {TeamRoleProjectionRepository, ChannelProjectionRepository} from '../repository/index.js';
import {ChannelMessageReadAccessService} from '../service/index.js';
import {startProjectionConsumer} from '../../common/kafka/projection-consumer.js';

type TeamRoleEvent = {
	eventType: 'ROLE_UPSERTED' | 'ROLE_DELETED' | 'ASSIGNMENT_UPSERTED' | 'ASSIGNMENT_DELETED' | 'MEMBER_ASSIGNMENTS_DELETED';
	teamId: number;
	roleId?: number;
	accountId?: number;
	name?: string;
	priority?: number;
	isDefault?: boolean;
	permissions?: Record<string, boolean>;
	occurredAt: string;
};

@injectable()
export class TeamRoleEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(TeamRoleEventConsumer.name);
	private consumer!: Consumer;
	private io?: Server;

	@inject()
	private readonly configService!: ConfigService;

	@inject()
	private readonly repository!: TeamRoleProjectionRepository;

	@inject()
	private readonly channels!: ChannelProjectionRepository;

	@inject()
	private readonly accessService!: ChannelMessageReadAccessService;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
		if (!this.isTeamRoleEvent(payload)) {
			throw new ProjectionContractError('invalid team role event payload');
		}

		this.assertKey(payload, messageKey);
		const eventTime = parseEventTime(payload.occurredAt);
		if (!eventTime) {
			throw new ProjectionContractError('team role event occurredAt must be RFC3339');
		}

		let affectedUserIds: number[] | undefined;
		switch (payload.eventType) {
			case 'ROLE_UPSERTED': {
				await this.repository.upsertRole({
					teamId: payload.teamId,
					roleId: payload.roleId!,
					name: payload.name!,
					priority: payload.priority!,
					isDefault: payload.isDefault!,
					permissions: payload.permissions!,
					...eventTime,
				});

				break;
			}

			case 'ROLE_DELETED': {
				await this.repository.deleteRole(payload.teamId, payload.roleId!, eventTime.occurredAt, eventTime.sourceVersion);

				break;
			}

			case 'ASSIGNMENT_UPSERTED': {
				await this.repository.upsertAssignment({
					teamId: payload.teamId,
					accountId: payload.accountId!,
					roleId: payload.roleId!,
					...eventTime,
				});
				affectedUserIds = [payload.accountId!];

				break;
			}

			case 'ASSIGNMENT_DELETED': {
				await this.repository.deleteAssignment({
					teamId: payload.teamId, accountId: payload.accountId!, roleId: payload.roleId!, occurredAt: eventTime.occurredAt, sourceVersion: eventTime.sourceVersion,
				});
				affectedUserIds = [payload.accountId!];

				break;
			}

			case 'MEMBER_ASSIGNMENTS_DELETED': {
				await this.repository.deleteMemberAssignments(
					payload.teamId,
					payload.accountId!,
					eventTime.occurredAt,
					eventTime.sourceVersion,
				);
				affectedUserIds = [payload.accountId!];
			}
		}

		const affectedChannelIds = await this.channels.findIdsByTeamId(payload.teamId);
		if (this.io && affectedChannelIds.length > 0) {
			await this.accessService.evictUnauthorizedSockets(this.io, affectedChannelIds, affectedUserIds);
		}
	}

	private isTeamRoleEvent(payload: unknown): payload is TeamRoleEvent {
		if (typeof payload !== 'object' || payload === null) {
			return false;
		}

		const event = payload as Partial<TeamRoleEvent>;
		if (!['ROLE_UPSERTED', 'ROLE_DELETED', 'ASSIGNMENT_UPSERTED', 'ASSIGNMENT_DELETED', 'MEMBER_ASSIGNMENTS_DELETED']
			.includes(event.eventType ?? '')
			|| !isSafePositiveInteger(event.teamId)
			|| parseEventTime(event.occurredAt) === null) {
			return false;
		}

		if (event.eventType === 'ROLE_UPSERTED') {
			return isSafePositiveInteger(event.roleId)
				&& typeof event.name === 'string' && event.name.length > 0
				&& Number.isSafeInteger(event.priority)
				&& typeof event.isDefault === 'boolean'
				&& isBooleanRecord(event.permissions);
		}

		if (event.eventType === 'ROLE_DELETED') {
			return isSafePositiveInteger(event.roleId);
		}

		return event.eventType === 'MEMBER_ASSIGNMENTS_DELETED' ? isSafePositiveInteger(event.accountId) : isSafePositiveInteger(event.roleId) && isSafePositiveInteger(event.accountId);
	}

	private assertKey(event: TeamRoleEvent, messageKey?: string): void {
		let expected: string;
		if (event.eventType === 'ROLE_UPSERTED' || event.eventType === 'ROLE_DELETED') {
			expected = `role:${event.teamId}:${event.roleId}`;
		} else if (event.eventType === 'MEMBER_ASSIGNMENTS_DELETED') {
			expected = `member:${event.teamId}:${event.accountId}`;
		} else {
			expected = `assignment:${event.teamId}:${event.accountId}:${event.roleId}`;
		}

		if (messageKey !== expected) {
			throw new ProjectionContractError(
				`team role event key mismatch [key=${messageKey ?? '<missing>'}, expected=${expected}]`,
			);
		}
	}

	setSocketServer(io: Server): void {
		this.io = io;
	}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-chat-team-role-event',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.teamRole;
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
