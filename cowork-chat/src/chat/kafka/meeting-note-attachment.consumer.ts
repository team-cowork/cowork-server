import {
	Injectable as injectable,
	Logger,
	OnModuleInit,
	OnModuleDestroy,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {startProjectionConsumer} from '../../common/kafka/projection-consumer.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {MeetingNoteAttachmentProjectionRepository} from '../repository/meeting-note-attachment-projection.repository.js';
import {type MeetingNoteAttachment} from '../schema/meeting-note-attachment-projection.schema.js';

type AttachmentEvent = {noteId: number; channelId: number; authorId: number; attachments: MeetingNoteAttachment[]; deleted: boolean; occurredAt: string};

export function parseMeetingNoteAttachmentEvent(payload: unknown, key?: string) {
	const event = payload as Partial<AttachmentEvent> | undefined;
	if (!event || !isSafePositiveInteger(event.noteId) || key !== String(event.noteId)
		|| !isSafePositiveInteger(event.channelId) || !isSafePositiveInteger(event.authorId)
		|| typeof event.deleted !== 'boolean' || !Array.isArray(event.attachments) || event.attachments.length > 20) {
		throw new ProjectionContractError('Invalid meeting note attachment state');
	}

	const time = parseEventTime(event.occurredAt);
	const ownedKey = new RegExp(String.raw`^chat-files/${event.channelId}/${event.authorId}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[a-z0-9]+$`, 'v');
	if (!time || event.attachments.some((a: MeetingNoteAttachment | undefined) => !a || typeof a.objectKey !== 'string'
		|| a.objectKey.length > 200 || !ownedKey.test(a.objectKey) || typeof a.name !== 'string' || a.name.trim() === '' || a.name.length > 255)
	|| new Set(event.attachments.map(a => a.objectKey)).size !== event.attachments.length
	|| (event.deleted && event.attachments.length > 0)) {
		throw new ProjectionContractError('Invalid meeting note attachment details');
	}

	return {...event as AttachmentEvent, ...time};
}

@injectable()
export class MeetingNoteAttachmentConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(MeetingNoteAttachmentConsumer.name);
	private consumer!: Consumer;

	constructor(
		private readonly config: ConfigService,
		private readonly readiness: ProjectionReadinessService,
		private readonly repository: MeetingNoteAttachmentProjectionRepository,
	) {}

	async onModuleInit() {
		const kafka = new Kafka({clientId: 'cowork-chat-meeting-note-attachments', ...getKafkaClientConfig(this.config)});
		const stream = PROJECTION_STREAMS.meetingNoteAttachment;
		this.consumer = kafka.consumer({groupId: stream.groupId});
		await startProjectionConsumer({
			logger: this.logger, kafka, consumer: this.consumer, readiness: this.readiness, stream,
			handleEvent: async (payload, key) => this.repository.apply(parseMeetingNoteAttachmentEvent(payload, key)),
		});
	}

	async onModuleDestroy() {
		await this.consumer.disconnect();
	}
}
