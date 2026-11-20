import {Injectable as injectable} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {SendMessageDto} from '../dto/index.js';
import {MessageScope} from '../service/index.js';
import {ConfirmedAttachment} from '../../storage/attachment-upload.schema.js';
import {LazyKafkaProducer} from '../../common/kafka/lazy-kafka-producer.js';
import {CHAT_MESSAGE_CONTRACT_VERSION, CHAT_MESSAGE_TOPIC} from './event/chat-message-contract.js';
import {ChatMessageEvent} from './event/chat-message.event.js';

export type MessageSendInput = Pick<SendMessageDto, 'content' | 'type' | 'parentMessageId' | 'threadId' | 'clientMessageId'>
	& MessageScope & {attachments?: ConfirmedAttachment[]};

@injectable()
export class ChatMessageProducer extends LazyKafkaProducer {
	constructor(configService: ConfigService) {
		super(configService, {
			clientId: 'cowork-chat',
			label: 'Kafka producer',
			startupRetryLabel: 'Kafka producer startup: chat.message.event.v3',
		});
	}

	/**
     같은 채널의 메시지가 순서대로 전달되도록 `channelId`를 키로 쓴다.
     */
	async sendMessage(channelId: number, dto: MessageSendInput, authorId: number): Promise<void> {
		const event: ChatMessageEvent = {
			contractVersion: CHAT_MESSAGE_CONTRACT_VERSION,
			eventType: 'MESSAGE_SENT',
			teamId: dto.teamId ?? null,
			projectId: dto.projectId ?? null,
			channelId,
			authorId,
			content: dto.content,
			type: dto.type ?? 'TEXT',
			attachments: dto.attachments,
			parentMessageId: dto.parentMessageId,
			threadId: dto.threadId,
			clientMessageId: dto.clientMessageId,
			occurredAt: new Date().toISOString(),
		};

		await this.publish({
			topic: CHAT_MESSAGE_TOPIC,
			messages: [
				{
					key: channelId.toString(),
					value: JSON.stringify(event),
				},
			],
		});
	}
}
