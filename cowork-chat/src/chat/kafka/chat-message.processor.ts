import {Injectable as injectable, Logger} from '@nestjs/common';
import {Types} from 'mongoose';
import {Server} from 'socket.io';
import {MessageRepository, toMessageBroadcastPayload} from '../repository/index.js';
import {type MessageDocument} from '../schema/index.js';
import {ChannelMessageReadAccessService, ChannelReadService} from '../service/index.js';
import {parseMentions} from '../util/index.js';
import {ChatMessageEvent} from './event/chat-message.event.js';
import {ChatMessageScopeValidator} from './chat-message-scope-validator.js';

/**
 저장한 메시지를 소켓으로 보내지 못했다. poison record가 아니므로 offset을 커밋하지 않고 Kafka 재전달을 기다린다.
 */
export class ChatMessageBroadcastSkippedError extends Error {
	constructor(channelId: number, messageId: string) {
		super(`chat.message broadcast skipped channelId=${channelId} messageId=${messageId}`);
		this.name = 'ChatMessageBroadcastSkippedError';
	}
}

/**
 정상 consumer와 quarantine 재처리가 공유하는 메시지 처리 경로.

 검색 색인은 메시지 저장과 같은 쓰기에 열리는 색인 아웃박스가 담당한다. 여기서 Elasticsearch를
 직접 호출하지 않으므로 색인 쓰기 실패가 메시지 저장이나 브로드캐스트를 막지 않는다.
 */
@injectable()
export class ChatMessageProcessor {
	private readonly logger = new Logger(ChatMessageProcessor.name);
	private io?: Server;

	constructor(
		private readonly messageRepository: MessageRepository,
		private readonly channelReadService: ChannelReadService,
		private readonly channelMessageReadAccess: ChannelMessageReadAccessService,
		private readonly scopeValidator: ChatMessageScopeValidator,
	) {}

	private async broadcast(event: ChatMessageEvent, saved: MessageDocument): Promise<void> {
		const messageId = saved._id.toString();
		const wasSent = await this.channelMessageReadAccess.emitToReadableChannelUsers(this.io, event.channelId, {event: 'message', payload: toMessageBroadcastPayload(saved)});
		if (wasSent) {
			// 기록이 실패해도 전송은 끝났으므로 다시 던지지 않는다. 다시 던지면 재전달로 같은 메시지를 한 번 더 보낸다.
			await this.messageRepository.markBroadcasted(saved._id).catch((error: unknown) => {
				this.logger.warn(`Failed to record message broadcast messageId=${messageId}: ${String(error)}`);
			});
		} else if (event.clientMessageId === undefined) {
			// 재전달하면 `clientMessageId`가 없어 같은 메시지를 한 번 더 저장하므로, 다시 던지지 않고 넘어간다.
			this.logger.warn(`Message broadcast skipped without retry key channelId=${event.channelId} messageId=${messageId}`);
		} else {
			// `broadcastedAt`을 비워 두어야 재전달 때 중복 경로가 이 메시지를 다시 보낸다.
			this.logger.warn(`Message broadcast skipped, waiting for Kafka redelivery channelId=${event.channelId} messageId=${messageId}`);
			throw new ChatMessageBroadcastSkippedError(event.channelId, messageId);
		}

		// 작성자는 자기 메시지까지 읽은 것이므로 읽음 위치와 함께 안읽음 수 캐시·표시도 맞춘다.
		void this.channelReadService.advance(event.channelId, event.authorId, saved._id)
			.then(unreadCount => {
				if (unreadCount !== null) {
					this.io?.to(`user:${event.authorId}`).emit('channel:unread:updated', {channelId: event.channelId, unreadCount});
				}
			})
			.catch((error: unknown) => {
				this.logger.warn(`Failed to update lastReadMessageId channelId=${event.channelId} authorId=${event.authorId}: ${String(error)}`);
			});
	}

	setSocketServer(io: Server): void {
		this.io = io;
	}

	async process(event: ChatMessageEvent): Promise<void> {
		await this.scopeValidator.validate(event);
		const mentions = parseMentions(event.content);
		this.logger.log(`message received channelId=${event.channelId} authorId=${event.authorId} type=${event.type} contentLength=${event.content.length}`);
		let saved: MessageDocument;
		try {
			saved = await this.messageRepository.createMessage({
				teamId: event.teamId,
				projectId: event.projectId ?? null,
				channelId: event.channelId,
				authorId: event.authorId,
				content: event.content,
				type: event.type,
				attachments: event.attachments ?? [],
				parentMessageId: event.parentMessageId === undefined || event.parentMessageId === ''
					? null
					: new Types.ObjectId(event.parentMessageId),
				threadId: event.threadId ?? null,
				clientMessageId: event.clientMessageId,
				mentions,
				notificationStatus: 'PENDING',
			});
			this.logger.log(`message saved messageId=${saved._id.toString()} channelId=${event.channelId}`);
		} catch (error) {
			if (!(typeof error === 'object' && error !== null && 'code' in error && error.code === 11_000)) {
				this.logger.error('Failed to save message — Kafka will redeliver (offset not committed)', error);
				throw error;
			}

			// 저장 뒤 브로드캐스트가 실패해 재전달된 경우에만 다시 전송한다. 이미 전송한 메시지를 다시 보내면 클라이언트에 중복으로 보인다.
			const existing = event.clientMessageId === undefined
				? null
				: await this.messageRepository.findUnbroadcastedByClientMessageId(event.clientMessageId);
			if (existing?.channelId !== event.channelId || existing.authorId !== event.authorId) {
				this.logger.warn(`Duplicate message detected, skipping (clientMessageId: ${event.clientMessageId})`);
				return;
			}

			this.logger.warn(`Duplicate message detected, re-broadcasting (clientMessageId: ${event.clientMessageId})`);
			saved = existing;
		}

		await this.broadcast(event, saved);
	}
}
