import { BadRequestException, ForbiddenException, Injectable, NotFoundException } from '@nestjs/common';
import { ConversationRepository } from './conversation.repository';
import { DmConversationDocument } from './schema/dm-conversation.schema';
import { ConversationResponseDto } from './dto/conversation-response.dto';

/** DM 대화방 개설·조회·숨기기를 담당하는 서비스. */
@Injectable()
export class ConversationService {
    constructor(private readonly conversationRepository: ConversationRepository) {}

    /**
     * 두 사용자 간 DM 대화방을 개설하거나 기존 대화방을 반환한다.
     *
     * @param userId - 요청한 사용자 ID
     * @param targetUserId - 대화 상대 사용자 ID
     * @returns 개설되거나 기존에 존재하는 대화방
     * @throws BadRequestException 자기 자신에게 DM 을 시도한 경우
     */
    async openConversation(userId: number, targetUserId: number): Promise<ConversationResponseDto> {
        if (userId === targetUserId) throw new BadRequestException('자기 자신과 DM을 시작할 수 없습니다');

        const conversation = await this.conversationRepository.findOrCreate(userId, targetUserId);
        return new ConversationResponseDto(conversation);
    }

    /**
     * 사용자가 참여 중이고 숨기지 않은 대화방 목록을 최신 메시지 순으로 반환한다.
     *
     * @param userId - 조회할 사용자 ID
     * @returns 대화방 목록 (lastMessageAt 내림차순)
     */
    async getMyConversations(userId: number): Promise<ConversationResponseDto[]> {
        const conversations = await this.conversationRepository.findVisibleByUserId(userId);
        return conversations.map((c) => new ConversationResponseDto(c));
    }

    /**
     * 대화방을 숨긴다 (Discord의 "닫기"에 해당).
     *
     * 상대방이 메시지를 보내면 자동으로 다시 노출된다.
     *
     * @param conversationId - 숨길 대화방 ID
     * @param userId - 요청한 사용자 ID
     * @throws NotFoundException 대화방이 존재하지 않는 경우
     * @throws ForbiddenException 사용자가 대화방 참여자가 아닌 경우
     */
    async hideConversation(conversationId: string, userId: number): Promise<void> {
        const conversation = await this.conversationRepository.findById(conversationId);
        if (!conversation) throw new NotFoundException('대화방을 찾을 수 없습니다');

        const isParticipant = conversation.participants.some((p) => p.userId === userId);
        if (!isParticipant) throw new ForbiddenException('접근 권한이 없습니다');

        await this.conversationRepository.hideForUser(conversationId, userId);
    }

    /**
     * 대화방을 조회하며 존재하지 않거나 접근 권한이 없으면 예외를 던진다.
     *
     * @param conversationId - 조회할 대화방 ID
     * @param userId - 요청한 사용자 ID
     * @returns 조회된 대화방 도큐먼트
     * @throws NotFoundException 대화방이 존재하지 않는 경우
     * @throws ForbiddenException 사용자가 대화방 참여자가 아닌 경우
     */
    async getConversationOrThrow(conversationId: string, userId: number): Promise<DmConversationDocument> {
        const conversation = await this.conversationRepository.findById(conversationId);
        if (!conversation) throw new NotFoundException('대화방을 찾을 수 없습니다');

        const isParticipant = conversation.participants.some((p) => p.userId === userId);
        if (!isParticipant) throw new ForbiddenException('접근 권한이 없습니다');

        return conversation;
    }
}
