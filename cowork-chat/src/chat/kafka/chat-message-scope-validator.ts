import { Injectable } from '@nestjs/common';
import { ChatMessageEvent } from './event/chat-message.event';
import { ChannelProjectionRepository } from '../repository/channel-projection.repository';
import { MessageRepository } from '../repository/message.repository';

export class ChatMessageScopeError extends Error {
    constructor(
        readonly reasonCode: 'CHANNEL_NOT_FOUND' | 'CHANNEL_SCOPE_MISMATCH' | 'PARENT_NOT_FOUND',
        message: string,
    ) {
        super(message);
        this.name = ChatMessageScopeError.name;
    }
}

/** 현재 로컬 읽기 모델을 기준으로 메시지의 채널·부모 리소스 귀속을 검증한다. */
@Injectable()
export class ChatMessageScopeValidator {
    constructor(
        private readonly channelProjectionRepository: ChannelProjectionRepository,
        private readonly messageRepository: MessageRepository,
    ) {}

    async validate(event: ChatMessageEvent): Promise<void> {
        const channel = await this.channelProjectionRepository.findById(event.channelId);
        if (!channel) {
            throw new ChatMessageScopeError('CHANNEL_NOT_FOUND', 'channel projection was not found');
        }
        if (channel.teamId !== event.teamId || channel.projectId !== (event.projectId ?? null)) {
            throw new ChatMessageScopeError('CHANNEL_SCOPE_MISMATCH', 'event teamId or projectId does not match channel');
        }
        if (event.parentMessageId) {
            const parent = await this.messageRepository.findByIdAndChannelId(event.parentMessageId, event.channelId);
            if (!parent) {
                throw new ChatMessageScopeError('PARENT_NOT_FOUND', 'parent message was not found in channel');
            }
        }
    }
}
