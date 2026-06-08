import {
    ForbiddenException,
    forwardRef,
    Inject,
    Injectable,
    NotFoundException,
} from '@nestjs/common';
import { Types } from 'mongoose';
import { MessageRepository } from './message.repository';
import { ConversationRepository } from '../conversation/conversation.repository';
import { BlockService } from '../block/block.service';
import { DmGateway } from '../gateway/dm.gateway';
import { SendMessageDto } from './dto/send-message.dto';
import { MessageResponseDto } from './dto/message-response.dto';

/** DM 메시지 CRUD·리액션·읽음 처리를 담당하는 서비스. */
@Injectable()
export class MessageService {
    constructor(
        private readonly messageRepository: MessageRepository,
        private readonly conversationRepository: ConversationRepository,
        private readonly blockService: BlockService,
        @Inject(forwardRef(() => DmGateway))
        private readonly gateway: DmGateway,
    ) {}

    /**
     * 대화방 메시지를 커서 기반으로 최신순 조회한다.
     *
     * @param conversationId - 대화방 ID
     * @param userId - 요청한 사용자 ID (참여자 검증에 사용)
     * @param before - 이 메시지 ID 이전 메시지만 반환하는 커서 (생략 시 최신부터)
     * @returns 최대 100건의 메시지 목록
     * @throws NotFoundException 대화방이 존재하지 않는 경우
     * @throws ForbiddenException 사용자가 대화방 참여자가 아닌 경우
     */
    async getMessages(conversationId: string, userId: number, before?: string): Promise<MessageResponseDto[]> {
        const conversation = await this.conversationRepository.findById(conversationId);
        if (!conversation) throw new NotFoundException('대화방을 찾을 수 없습니다');

        const isParticipant = conversation.participants.some((p) => p.userId === userId);
        if (!isParticipant) throw new ForbiddenException('접근 권한이 없습니다');

        const rows = await this.messageRepository.findMessages(conversationId, before);
        return rows.map((r) => new MessageResponseDto(r, userId));
    }

    /**
     * 메시지를 전송한다.
     *
     * 수신자가 발신자를 차단한 경우 전송이 거부된다.
     * 메시지는 `notificationStatus=PENDING` 으로 생성되며, Outbox Poller 가 별도로 알림을 발행한다.
     * 전송 성공 시 WebSocket 으로 `message:new` 이벤트를 브로드캐스트한다.
     *
     * @param conversationId - 대화방 ID
     * @param userId - 발신자 ID
     * @param dto - 전송할 메시지 데이터
     * @returns 생성된 메시지
     * @throws NotFoundException 대화방이 존재하지 않는 경우
     * @throws ForbiddenException 참여자가 아니거나 수신자에게 차단된 경우
     */
    async sendMessage(conversationId: string, userId: number, dto: SendMessageDto): Promise<MessageResponseDto> {
        const conversation = await this.conversationRepository.findById(conversationId);
        if (!conversation) throw new NotFoundException('대화방을 찾을 수 없습니다');

        const isParticipant = conversation.participants.some((p) => p.userId === userId);
        if (!isParticipant) throw new ForbiddenException('접근 권한이 없습니다');

        const receiverId = this.conversationRepository.getOtherParticipantId(conversation, userId);
        if (receiverId !== null) {
            const isBlocked = await this.blockService.isBlocked(receiverId, userId);
            if (isBlocked) throw new ForbiddenException('차단된 사용자에게 메시지를 보낼 수 없습니다');
        }

        const message = await this.messageRepository.createMessage({
            conversationId: conversation._id as Types.ObjectId,
            authorId: userId,
            content: dto.content,
            type: dto.type ?? 'TEXT',
            attachments: dto.attachments ?? [],
            clientMessageId: dto.clientMessageId,
            mentions: dto.mentions ?? [],
            notificationStatus: 'PENDING',
        });

        const messageId = message._id as Types.ObjectId;

        await Promise.all([
            this.conversationRepository.onMessageSent(conversation._id as Types.ObjectId, userId, messageId, message.createdAt),
            receiverId !== null
                ? this.conversationRepository.onMessageReceived(conversation._id as Types.ObjectId, receiverId, messageId, message.createdAt)
                : Promise.resolve(),
        ]);

        const response = new MessageResponseDto({
            _id: messageId,
            conversationId: conversation._id as Types.ObjectId,
            authorId: message.authorId,
            content: message.content,
            type: message.type,
            attachments: message.attachments,
            isEdited: message.isEdited,
            clientMessageId: message.clientMessageId,
            mentions: message.mentions,
            reactions: message.reactions,
            createdAt: message.createdAt,
            updatedAt: message.updatedAt,
        }, userId);

        this.gateway.broadcastNewMessage(conversationId, response);

        return response;
    }

    /**
     * 메시지 내용을 수정한다.
     *
     * 편집 이력이 메시지에 기록되며, `isEdited` 플래그가 true 로 설정된다.
     * 수정 성공 시 WebSocket 으로 `message:updated` 이벤트를 브로드캐스트한다.
     *
     * @param conversationId - 대화방 ID
     * @param messageId - 수정할 메시지 ID
     * @param userId - 요청한 사용자 ID
     * @param content - 새 메시지 내용
     * @returns 수정된 메시지
     * @throws NotFoundException 메시지가 존재하지 않는 경우
     * @throws ForbiddenException 본인 메시지가 아닌 경우
     */
    async editMessage(conversationId: string, messageId: string, userId: number, content: string): Promise<MessageResponseDto> {
        const message = await this.messageRepository.findByIdAndConversationId(messageId, conversationId);
        if (!message) throw new NotFoundException('메시지를 찾을 수 없습니다');
        if (message.authorId !== userId) throw new ForbiddenException('본인 메시지만 수정할 수 있습니다');

        const updated = await this.messageRepository.updateContent(messageId, conversationId, content);
        if (!updated) throw new NotFoundException('메시지를 찾을 수 없습니다');

        const response = new MessageResponseDto({
            _id: updated._id as Types.ObjectId,
            conversationId: updated.conversationId,
            authorId: updated.authorId,
            content: updated.content,
            type: updated.type,
            attachments: updated.attachments,
            isEdited: updated.isEdited,
            clientMessageId: updated.clientMessageId,
            mentions: updated.mentions,
            reactions: updated.reactions,
            createdAt: updated.createdAt,
            updatedAt: updated.updatedAt,
        }, userId);

        this.gateway.broadcastMessageUpdated(conversationId, response);

        return response;
    }

    /**
     * 메시지를 삭제한다.
     *
     * 삭제 성공 시 WebSocket 으로 `message:deleted` 이벤트를 브로드캐스트한다.
     *
     * @param conversationId - 대화방 ID
     * @param messageId - 삭제할 메시지 ID
     * @param userId - 요청한 사용자 ID
     * @throws NotFoundException 메시지가 존재하지 않는 경우
     * @throws ForbiddenException 본인 메시지가 아닌 경우
     */
    async deleteMessage(conversationId: string, messageId: string, userId: number): Promise<void> {
        const message = await this.messageRepository.findByIdAndConversationId(messageId, conversationId);
        if (!message) throw new NotFoundException('메시지를 찾을 수 없습니다');
        if (message.authorId !== userId) throw new ForbiddenException('본인 메시지만 삭제할 수 있습니다');

        await this.messageRepository.deleteById(messageId);

        this.gateway.broadcastMessageDeleted(conversationId, messageId);
    }

    /**
     * 메시지에 이모지 리액션을 추가하거나 제거한다.
     *
     * 처리 성공 시 WebSocket 으로 `reaction:updated` 이벤트를 브로드캐스트한다.
     *
     * @param conversationId - 대화방 ID
     * @param messageId - 리액션을 추가·제거할 메시지 ID
     * @param userId - 요청한 사용자 ID
     * @param emoji - 이모지 문자열
     * @param action - `'ADD'` 추가 | `'REMOVE'` 제거
     * @throws NotFoundException 대화방 또는 메시지가 존재하지 않는 경우
     * @throws ForbiddenException 사용자가 대화방 참여자가 아닌 경우
     */
    async reactMessage(
        conversationId: string,
        messageId: string,
        userId: number,
        emoji: string,
        action: 'ADD' | 'REMOVE',
    ): Promise<void> {
        const conversation = await this.conversationRepository.findById(conversationId);
        if (!conversation) throw new NotFoundException('대화방을 찾을 수 없습니다');

        const isParticipant = conversation.participants.some((p) => p.userId === userId);
        if (!isParticipant) throw new ForbiddenException('접근 권한이 없습니다');

        const result = action === 'ADD'
            ? await this.messageRepository.addReaction(conversationId, messageId, emoji, userId)
            : await this.messageRepository.removeReaction(conversationId, messageId, emoji, userId);

        if (result === null) throw new NotFoundException('메시지를 찾을 수 없습니다');

        const updatedMessage = await this.messageRepository.findById(messageId);
        if (updatedMessage) {
            const reactions = (updatedMessage.reactions ?? []).map((r) => ({
                emoji: r.emoji,
                count: r.userIds.length,
                myReaction: r.userIds.includes(userId),
            }));
            this.gateway.broadcastReactionUpdated(conversationId, messageId, reactions);
        }
    }

    /**
     * 특정 메시지까지 읽음 처리한다.
     *
     * 참여자의 `unreadCount` 를 0 으로 초기화하고 `lastReadMessageId` 를 갱신한다.
     * 처리 성공 시 WebSocket 으로 `read:updated` 이벤트를 브로드캐스트한다.
     *
     * @param conversationId - 대화방 ID
     * @param userId - 읽음 처리할 사용자 ID
     * @param messageId - 읽은 마지막 메시지 ID
     * @throws NotFoundException 대화방이 존재하지 않는 경우
     * @throws ForbiddenException 사용자가 대화방 참여자가 아닌 경우
     */
    async markRead(conversationId: string, userId: number, messageId: string): Promise<void> {
        const conversation = await this.conversationRepository.findById(conversationId);
        if (!conversation) throw new NotFoundException('대화방을 찾을 수 없습니다');

        const isParticipant = conversation.participants.some((p) => p.userId === userId);
        if (!isParticipant) throw new ForbiddenException('접근 권한이 없습니다');

        await this.conversationRepository.markRead(conversationId, userId, messageId);

        this.gateway.broadcastReadUpdated(conversationId, userId, messageId);
    }
}
