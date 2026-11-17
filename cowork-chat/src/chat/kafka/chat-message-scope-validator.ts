import {Injectable as injectable} from '@nestjs/common';
import {ChannelProjectionRepository, MessageRepository, ThreadProjectionRepository} from '../repository/index.js';
import {checkThreadTarget, resolveMessageScope, type ThreadTargetError} from '../service/index.js';
import {ChatMessageEvent} from './event/chat-message.event.js';

export class ChatMessageScopeError extends Error {
	constructor(
		readonly reasonCode: 'CHANNEL_NOT_FOUND' | 'CHANNEL_ARCHIVED' | 'CHANNEL_SCOPE_MISMATCH' | 'PARENT_NOT_FOUND' | ThreadTargetError,
		message: string,
	) {
		super(message);
		this.name = ChatMessageScopeError.name;
	}
}

/**
현재 로컬 읽기 모델을 기준으로 메시지의 채널·부모 리소스 귀속을 검증한다.
*/
@injectable()
export class ChatMessageScopeValidator {
	constructor(
		private readonly channelProjectionRepository: ChannelProjectionRepository,
		private readonly messageRepository: MessageRepository,
		private readonly threadProjectionRepository: ThreadProjectionRepository,
	) {}

	async validate(event: ChatMessageEvent): Promise<void> {
		const channel = await this.channelProjectionRepository.findById(event.channelId);
		if (!channel) {
			throw new ChatMessageScopeError('CHANNEL_NOT_FOUND', 'channel projection was not found');
		}

		// 전송 시점 검사를 통과한 뒤 채널이 보관되었을 수 있으므로 저장 직전에 다시 확인한다.
		if (channel.archived === true) {
			throw new ChatMessageScopeError('CHANNEL_ARCHIVED', 'channel was archived before the message was stored');
		}

		const scope = resolveMessageScope(channel);
		if (scope?.teamId !== event.teamId || scope.projectId !== (event.projectId ?? null)) {
			throw new ChatMessageScopeError('CHANNEL_SCOPE_MISMATCH', 'event teamId or projectId does not match channel');
		}

		let parent;
		if (event.parentMessageId !== undefined && event.parentMessageId !== '') {
			parent = await this.messageRepository.findByIdAndChannelId(event.parentMessageId, event.channelId);
			if (!parent) {
				throw new ChatMessageScopeError('PARENT_NOT_FOUND', 'parent message was not found in channel');
			}
		}

		// 전송 시점 검사를 통과한 뒤 스레드가 보관되거나 지워졌을 수 있으므로 저장 직전에 다시 확인한다.
		const thread = event.threadId === undefined ? undefined : await this.threadProjectionRepository.findActive(event.threadId);
		const error = checkThreadTarget(event.channelId, event.threadId, thread, parent ?? undefined);
		if (error !== null) {
			throw new ChatMessageScopeError(error, `thread target check failed: ${error}`);
		}
	}
}
