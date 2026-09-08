import { Injectable, Logger } from '@nestjs/common';
import { Types } from 'mongoose';
import { Server } from 'socket.io';
import { ChatMessageEvent } from './event/chat-message.event';
import { MessageRepository } from '../repository/message.repository';
import { ChannelMemberRepository } from '../repository/channel-member.repository';
import { ChannelMessageReadAccessService } from '../service/channel-message-read-access.service';
import { ChatMessageScopeValidator } from './chat-message-scope-validator';

/**
 * 정상 consumer와 quarantine 재처리가 공유하는 메시지 처리 경로.
 *
 * 검색 색인은 메시지 저장과 같은 쓰기에 열리는 색인 아웃박스가 담당한다. 여기서 Elasticsearch를
 * 직접 호출하지 않으므로 색인 쓰기 실패가 메시지 저장이나 브로드캐스트를 막지 않는다.
 */
@Injectable()
export class ChatMessageProcessor {
    private readonly logger = new Logger(ChatMessageProcessor.name);
    private io?: Server;

    constructor(
        private readonly messageRepository: MessageRepository,
        private readonly channelMemberRepository: ChannelMemberRepository,
        private readonly channelMessageReadAccess: ChannelMessageReadAccessService,
        private readonly scopeValidator: ChatMessageScopeValidator,
    ) {}

    setSocketServer(io: Server): void {
        this.io = io;
    }

    async process(event: ChatMessageEvent): Promise<void> {
        await this.scopeValidator.validate(event);
        const mentions = this.parseMentions(event.content);
        this.logger.log(`message received channelId=${event.channelId} authorId=${event.authorId} type=${event.type} contentLength=${event.content.length}`);
        try {
            const saved = await this.messageRepository.createMessage({
                teamId: event.teamId,
                projectId: event.projectId ?? null,
                channelId: event.channelId,
                authorId: event.authorId,
                content: event.content,
                type: event.type,
                attachments: event.attachments ?? [],
                parentMessageId: event.parentMessageId ? new Types.ObjectId(event.parentMessageId) : null,
                clientMessageId: event.clientMessageId,
                mentions,
                notificationStatus: 'PENDING',
            });
            this.logger.log(`message saved messageId=${saved._id.toString()} channelId=${event.channelId}`);
            if (!this.io) {
                this.logger.warn(`Socket.IO server not initialized yet, dropping message broadcast (channelId=${event.channelId})`);
            } else {
                await this.channelMessageReadAccess.emitToReadableChannelUsers(this.io, event.channelId, 'message', saved.toObject());
            }
            void this.channelMemberRepository.updateLastRead(event.channelId, event.authorId, saved._id)
                .catch((error: unknown) => this.logger.warn(`Failed to update lastReadMessageId channelId=${event.channelId} authorId=${event.authorId}: ${String(error)}`));
        } catch (error) {
            if (typeof error === 'object' && error !== null && 'code' in error && error.code === 11000) {
                this.logger.warn(`Duplicate message detected, skipping (clientMessageId: ${event.clientMessageId})`);
                return;
            }
            this.logger.error('Failed to save message — Kafka will redeliver (offset not committed)', error);
            throw error;
        }
    }

    private parseMentions(content: string): number[] {
        return [...new Set([...content.matchAll(/<@(\d+)>/g)].map((match) => parseInt(match[1], 10)))];
    }
}
