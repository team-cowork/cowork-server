import {
    BadRequestException,
    ForbiddenException,
    forwardRef,
    Inject,
    Injectable,
    Logger,
    NotFoundException,
} from '@nestjs/common';
import { Types } from 'mongoose';
import { MessageDocument } from './schema/message.schema';
import { EditMessageDto } from './dto/edit-message.dto';
import { UserRole } from '../common/enum/user-role.enum';
import { ElasticsearchService } from '../search/elasticsearch.service';
import { MinioService } from '../storage/minio.service';
import { ChatMessageProducer } from './kafka/chat-message.producer';
import { GithubIssueProducer } from './kafka/github-issue.producer';
import { ProjectClient } from './service/project.client';
import { ChannelClient } from './service/channel.client';
import { UserClient } from './service/user.client';
import { ChatGateway } from './chat.gateway';
import { SendMessageDto } from './dto/send-message.dto';
import {
    ConfirmFileUploadRequestDto,
    CreateFileUploadUrlRequestDto,
    CreateFileUploadUrlResponseDto,
} from './dto/create-file-upload-url.dto';
import { CreateGithubIssueDto } from './dto/create-github-issue.dto';
import { SlashCommand, SlashCommandDto } from './dto/slash-command.dto';
import { SearchMessagesDto } from './dto/search-messages.dto';
import { SearchTeamMessagesDto } from './dto/search-team-messages.dto';
import { SearchMessagesResponseDto } from './dto/search-message-response.dto';
import { FileListQueryDto, FileListResponseDto } from './dto/file-list.dto';
import { MessageRepository, MessageRow } from './repository/message.repository';
import { ChannelMemberRepository } from './repository/channel-member.repository';
import { BlockService } from '../block/block.service';
import {
    ChannelUserContext,
    ChannelUserRoleContext,
    MessageUserRoleContext,
    UserContext,
} from './dto/context';

const SYSTEM_AUTHOR_ID = 0;
const SYSTEM_AUTHOR_NAME = 'System';
const FILE_SHARE_VIEW_TYPE = 'FILE_SHARE';
const DM_CHANNEL_TYPE = 'DM';

/**
 * 채팅 서비스의 핵심 비즈니스 로직 클래스.
 *
 * 채널 멤버십 검증, 메시지 CRUD, 파일 업로드/삭제, 이모지 반응,
 * 메시지 고정, Elasticsearch 검색, GitHub 이슈 발행을 담당한다.
 * Socket.IO 브로드캐스트는 `ChatGateway.server`를 통해 수행하며,
 * `ChatGateway`와 순환 의존 관계이므로 `forwardRef`로 주입한다.
 */
@Injectable()
export class ChatService {
    private readonly logger = new Logger(ChatService.name);

    constructor(
        private readonly messageRepository: MessageRepository,
        private readonly channelMemberRepository: ChannelMemberRepository,
        private readonly elasticsearchService: ElasticsearchService,
        private readonly minioService: MinioService,
        private readonly chatMessageProducer: ChatMessageProducer,
        private readonly githubIssueProducer: GithubIssueProducer,
        private readonly projectClient: ProjectClient,
        private readonly channelClient: ChannelClient,
        private readonly userClient: UserClient,
        private readonly blockService: BlockService,
        @Inject(forwardRef(() => ChatGateway))
        private readonly chatGateway: ChatGateway,
    ) {}

    /**
     * 사용자가 채널 멤버인지 확인한다.
     *
     * @param channelId - 확인할 채널 ID
     * @param userId - 확인할 사용자 ID
     * @returns 멤버이면 `true`, 아니면 `false`
     */
    async isMember(channelId: number, userId: number): Promise<boolean> {
        return this.channelMemberRepository.exists(channelId, userId);
    }

    async isTeamMember(teamId: number, userId: number): Promise<boolean> {
        return this.channelMemberRepository.existsByTeam(teamId, userId);
    }

    /**
     * 채널 멤버십을 검증한다. 멤버가 아니면 `ForbiddenException`을 던진다.
     *
     * @param channelId - 채널 ID
     * @param userId - 사용자 ID
     * @throws ForbiddenException 멤버가 아닌 경우
     */
    async checkMembership(channelId: number, userId: number): Promise<void> {
        const isMember = await this.channelMemberRepository.exists(channelId, userId);
        if (!isMember) throw new ForbiddenException('채널 접근 권한이 없습니다');
    }

    /**
     * 채널 멤버십을 검증하고 해당 채널의 팀 ID를 반환한다.
     * GitHub 이슈 생성 시 채널-팀 소속 검증에 사용한다.
     *
     * @param channelId - 채널 ID
     * @param userId - 사용자 ID
     * @returns 채널이 속한 팀 ID
     * @throws ForbiddenException 멤버가 아닌 경우
     */
    async checkMembershipAndGetTeamId(channelId: number, userId: number): Promise<number> {
        const teamId = await this.channelMemberRepository.findTeamIdByChannelAndUser(channelId, userId);
        if (teamId === null) throw new ForbiddenException('채널 접근 권한이 없습니다');
        return teamId;
    }

    /**
     * MinIO presigned 업로드 URL을 생성한다.
     * 반환된 `uploadUrl`에 PUT 요청 시 `headers`를 함께 전송해야 한다.
     *
     * @param ctx - 채널·사용자 컨텍스트
     * @param dto - 파일명, MIME 타입, 크기
     * @returns presigned URL, objectKey, fileUrl, 만료 시간, 필수 헤더
     */
    async createFileUploadUrl(
        ctx: ChannelUserContext,
        dto: CreateFileUploadUrlRequestDto,
    ): Promise<CreateFileUploadUrlResponseDto> {
        await this.checkMembership(ctx.channelId, ctx.userId);
        const upload = await this.minioService.createPresignedUpload({
            channelId: ctx.channelId,
            userId: ctx.userId,
            filename: dto.filename,
            contentType: dto.contentType,
            size: dto.size,
        });

        return {
            ...upload,
            headers: {
                'Content-Type': dto.contentType,
            },
        };
    }

    /**
     * 클라이언트가 업로드한 파일을 MinIO에서 검증하고 공개 URL을 반환한다.
     *
     * @param ctx - 채널·사용자 컨텍스트
     * @param dto - 검증할 objectKey
     * @returns 메시지 attachments.url에 사용할 파일 URL
     */
    async confirmFileUpload(ctx: ChannelUserContext, dto: ConfirmFileUploadRequestDto): Promise<string> {
        await this.checkMembership(ctx.channelId, ctx.userId);
        return this.minioService.confirmUpload(ctx.channelId, ctx.userId, dto.objectKey);
    }

    /**
     * FILE_SHARE 채널의 첨부파일 목록을 커서 기반 페이지네이션으로 조회한다.
     * FILE_SHARE 채널이 아니면 `BadRequestException`을 던진다.
     * 업로더 이름은 user-service에서 일괄 조회한다.
     *
     * @param ctx - 채널·사용자 컨텍스트
     * @param query - 커서, 페이지 크기
     * @returns 파일 목록과 다음 페이지 커서
     * @throws BadRequestException FILE_SHARE 채널이 아닌 경우
     */
    async getFileList(ctx: ChannelUserContext, query: FileListQueryDto): Promise<FileListResponseDto> {
        await this.checkMembership(ctx.channelId, ctx.userId);

        const channel = await this.channelClient.getChannel(ctx.channelId, ctx.userId);
        if (channel.viewType !== FILE_SHARE_VIEW_TYPE) {
            throw new BadRequestException('FILE_SHARE 채널에서만 파일 목록을 조회할 수 있습니다');
        }

        const limit = query.limit ?? 20;
        const { items, nextCursor } = await this.messageRepository.findFileAttachments(ctx.channelId, query.before, limit);
        const uploaderNames = await this.loadUploaderNames(items);

        return {
            files: items.map((item) => ({
                fileId: item.fileId,
                messageId: item.messageId,
                fileName: item.fileName,
                fileSize: item.fileSize,
                fileUrl: item.fileUrl,
                mimeType: item.mimeType,
                uploaderId: item.uploaderId,
                uploaderName: uploaderNames.get(item.uploaderId) ?? String(item.uploaderId),
                uploadedAt: item.uploadedAt,
            })),
            nextCursor,
        };
    }

    /**
     * 파일이 포함된 메시지와 MinIO 오브젝트를 삭제한다.
     * MinIO 삭제 실패는 경고 로그만 남기고 계속 진행한다(파일 삭제 부분 실패가 메시지 삭제를 막지 않음).
     * projectId가 있으면 Elasticsearch에서도 비동기로 제거한다.
     * 성공 시 `chat:{channelId}` 룸에 `message:deleted` 이벤트를 emit한다.
     *
     * @param ctx - 채널·사용자·역할 컨텍스트
     * @param fileId - base64 인코딩된 파일 식별자
     * @returns 삭제된 채널 ID와 메시지 ID
     * @throws BadRequestException fileId 디코딩 실패
     * @throws NotFoundException 메시지가 존재하지 않는 경우
     * @throws ForbiddenException 본인 파일이 아니고 ADMIN도 아닌 경우
     */
    async deleteFile(ctx: ChannelUserRoleContext, fileId: string): Promise<{ channelId: number; messageId: string }> {
        const messageId = this.decodeFileId(fileId);
        await this.checkMembership(ctx.channelId, ctx.userId);

        const message = await this.messageRepository.findByIdAndChannelId(messageId, ctx.channelId);
        if (!message) throw new NotFoundException('파일(메시지)을 찾을 수 없습니다');
        if (message.authorId !== ctx.userId && !this.isAdmin(ctx.userRole)) {
            throw new ForbiddenException('본인이 업로드한 파일만 삭제할 수 있습니다');
        }

        await Promise.all(
            message.attachments.map(async (attachment) => {
                try {
                    const objectKey = this.minioService.extractObjectKey(attachment.url);
                    if (!objectKey.startsWith(`chat-files/${ctx.channelId}/`)) {
                        this.logger.warn(`채널 범위를 벗어난 objectKey 삭제를 건너뜁니다 [key=${objectKey}]`);
                        return;
                    }
                    await this.minioService.removeObject(objectKey);
                } catch (error) {
                    this.logger.warn(`MinIO 파일 삭제 실패 [url=${attachment.url}]`, error);
                }
            }),
        );

        await this.messageRepository.deleteById(messageId);
        if (message.projectId) {
            this.elasticsearchService.deleteMessage(messageId).catch((err) =>
                this.logger.error(`ES deleteMessage 실패 [messageId=${messageId}]`, err),
            );
        }

        this.chatGateway.server
            ?.to(`chat:${ctx.channelId}`)
            .emit('message:deleted', { messageId });

        return { channelId: ctx.channelId, messageId };
    }

    /**
     * 메시지를 Kafka `chat.message` 토픽으로 발행한다.
     * 실제 저장과 Socket.IO 브로드캐스트는 `ChatMessageConsumer`가 처리한다.
     *
     * @param ctx - 채널·사용자·역할 컨텍스트
     * @param dto - 메시지 내용, 타입, 첨부파일 등
     */
    async sendMessage(ctx: ChannelUserRoleContext, dto: SendMessageDto): Promise<void> {
        const membership = await this.channelMemberRepository.findMembership(ctx.channelId, ctx.userId);
        if (!membership) throw new ForbiddenException('채널 접근 권한이 없습니다');

        if (dto.attachments?.length) {
            for (const attachment of dto.attachments) {
                this.minioService.assertOwnedAttachmentUrl(attachment.url, ctx.channelId, ctx.userId);
            }
        }

        if (membership.channelType === DM_CHANNEL_TYPE) {
            await this.verifyDmSendable(ctx.channelId, ctx.userId);
            dto = { ...dto, teamId: null, projectId: null };
        } else {
            dto = { ...dto, teamId: membership.teamId };
        }

        await this.chatMessageProducer.sendMessage(ctx.channelId, dto, ctx.userId, ctx.userRole);
    }

    /**
     * DM 메시지 전송 가능 여부를 검사한다.
     * 수신자가 발신자를 차단했으면 `ForbiddenException`을 던지고,
     * 전송 가능하면 양쪽 멤버의 숨김(isHidden) 상태를 해제해 대화 목록에 다시 노출시킨다.
     *
     * @param channelId - DM 채널 ID
     * @param senderId - 발신자 사용자 ID
     * @throws ForbiddenException 수신자가 발신자를 차단한 경우
     */
    private async verifyDmSendable(channelId: number, senderId: number): Promise<void> {
        const members = await this.channelMemberRepository.findByChannelId(channelId);
        const receiver = members.find((member) => member.userId !== senderId);
        if (!receiver) return;

        if (await this.blockService.isBlocked(receiver.userId, senderId)) {
            throw new ForbiddenException('상대방이 회원님을 차단하여 메시지를 보낼 수 없습니다');
        }
        await Promise.all([
            this.channelMemberRepository.setHidden(channelId, senderId, false),
            this.channelMemberRepository.setHidden(channelId, receiver.userId, false),
        ]);
    }

    /**
     * 내 DM 대화 목록을 조회한다. 숨긴(isHidden) 대화는 제외되며,
     * 마지막 메시지 시각 내림차순으로 정렬된다.
     *
     * @param userId - 조회할 사용자 ID
     * @returns 채널 ID, 상대 사용자 ID, 안읽음 수, 마지막 메시지 요약 목록
     */
    async getMyDms(userId: number) {
        const memberships = await this.channelMemberRepository.findDmMemberships(userId);
        if (memberships.length === 0) return [];

        const channelIds = memberships.map((m) => m.channelId);
        const [others, lastMessages, unreadCounts] = await Promise.all([
            this.channelMemberRepository.findOtherDmMembers(channelIds, userId),
            this.messageRepository.findLastMessages(channelIds),
            this.messageRepository.countUnreadForChannels(memberships),
        ]);

        return memberships
            .map(({ channelId }) => ({
                channelId,
                otherUserId: others.get(channelId) ?? null,
                unreadCount: unreadCounts.get(channelId) ?? 0,
                lastMessage: lastMessages.get(channelId) ?? null,
            }))
            .sort((a, b) => (b.lastMessage?.createdAt?.getTime() ?? 0) - (a.lastMessage?.createdAt?.getTime() ?? 0));
    }

    /**
     * DM 대화를 목록에서 숨긴다 (Discord의 "닫기").
     * 상대방이 메시지를 보내면 자동으로 다시 노출된다.
     *
     * @param channelId - 숨길 DM 채널 ID
     * @param userId - 요청 사용자 ID
     * @throws ForbiddenException DM 채널 멤버가 아닌 경우
     */
    async hideDm(channelId: number, userId: number): Promise<void> {
        const membership = await this.channelMemberRepository.findMembership(channelId, userId);
        if (!membership || membership.channelType !== DM_CHANNEL_TYPE) {
            throw new ForbiddenException('DM 채널 접근 권한이 없습니다');
        }
        await this.channelMemberRepository.setHidden(channelId, userId, true);
    }

    /**
     * 슬래시 커맨드를 처리한다. 현재 `github.issue.create`만 지원한다.
     * 지원하지 않는 커맨드는 `BadRequestException`을 던진다.
     *
     * @param ctx - 채널·사용자 컨텍스트
     * @param dto - 커맨드 종류와 payload
     * @throws BadRequestException 지원하지 않는 커맨드인 경우
     */
    async handleSlashCommand(ctx: ChannelUserContext, dto: SlashCommandDto): Promise<void> {
        if (dto.command !== SlashCommand.GITHUB_ISSUE_CREATE) {
            throw new BadRequestException('지원하지 않는 슬래시 커맨드입니다');
        }
        await this.publishGithubIssueCreateCommand(ctx, dto.payload);
    }

    /**
     * GitHub 이슈 생성 커맨드를 Kafka `github.issue.create` 토픽으로 발행한다.
     * 채널의 팀과 프로젝트의 팀이 일치하는지 검증한다.
     *
     * @param ctx - 채널·사용자 컨텍스트
     * @param dto - GitHub 이슈 생성 정보 (projectId, title, body)
     * @throws BadRequestException 프로젝트에 GitHub 레포지토리 정보가 없는 경우
     * @throws ForbiddenException 프로젝트가 채널 팀에 속하지 않는 경우
     */
    async publishGithubIssueCreateCommand(ctx: ChannelUserContext, dto: CreateGithubIssueDto): Promise<void> {
        const channelTeamId = await this.checkMembershipAndGetTeamId(ctx.channelId, ctx.userId);
        const repoInfo = await this.projectClient.getGithubRepoInfo(dto.projectId);
        if (!repoInfo) {
            throw new BadRequestException('프로젝트 GitHub 레포지토리 정보를 찾을 수 없습니다');
        }
        if (channelTeamId !== repoInfo.teamId) {
            throw new ForbiddenException('해당 프로젝트는 이 채널의 팀에 속하지 않습니다');
        }

        await this.githubIssueProducer.send({
            channelId: ctx.channelId,
            teamId: repoInfo.teamId,
            projectId: dto.projectId,
            owner: repoInfo.owner,
            repo: repoInfo.repo,
            title: dto.title,
            body: dto.body,
            requesterId: ctx.userId,
        });
    }

    /**
     * 채널 메시지를 최신순으로 커서 기반 페이지네이션 조회한다.
     * 응답의 `reactions`에는 요청자의 반응 여부(`myReaction`)가 포함된다.
     *
     * @param ctx - 채널·사용자 컨텍스트
     * @param before - 이 MongoDB ObjectId 이전의 메시지 조회 (없으면 최신부터)
     * @returns 최대 100개의 메시지 배열
     */
    async getMessages(ctx: ChannelUserContext, before?: string, parentMessageId?: string) {
        await this.checkMembership(ctx.channelId, ctx.userId);
        const rows = await this.messageRepository.findMessages(ctx.channelId, before, parentMessageId);
        return rows.map(row => this.toMessageResponse(row, ctx.userId));
    }

    /**
     * 프로젝트 내 채널 메시지를 Elasticsearch로 전문 검색한다.
     * 요청자가 접근 가능한 채널로 검색 범위를 자동으로 제한한다.
     * `dto.channelId`가 있으면 해당 채널 접근 권한도 검증한다.
     *
     * @param projectId - 검색 대상 프로젝트 ID
     * @param dto - 검색 조건 (키워드, 채널, 작성자, 타입, 파일 포함 여부, 커서, 페이지 크기)
     * @param ctx - 사용자 컨텍스트
     * @returns 검색 결과와 다음 페이지 커서
     * @throws ForbiddenException 프로젝트 멤버가 아니거나 채널 접근 권한이 없는 경우
     */
    async searchProjectMessages(
        projectId: number,
        dto: SearchMessagesDto,
        ctx: UserContext,
    ): Promise<SearchMessagesResponseDto> {
        const isMember = await this.projectClient.isMember(projectId, ctx.userId);
        if (!isMember) {
            throw new ForbiddenException('프로젝트 접근 권한이 없습니다');
        }

        const accessibleChannelIds = await this.channelMemberRepository.findChannelIdsByUser(ctx.userId);

        let filteredChannelIds = accessibleChannelIds;
        if (dto.channelId !== undefined) {
            if (!accessibleChannelIds.includes(dto.channelId)) {
                throw new ForbiddenException('채널 접근 권한이 없습니다');
            }
            filteredChannelIds = [dto.channelId];
        }

        const { hits, nextCursor } = await this.elasticsearchService.searchMessages({
            projectId,
            accessibleChannelIds: filteredChannelIds,
            q: dto.q,
            authorId: dto.authorId,
            type: dto.type,
            hasFile: dto.hasFile,
            before: dto.before,
            limit: dto.limit ?? 50,
        });

        return { messages: hits, nextCursor };
    }

    async searchTeamMessages(
        teamId: number,
        dto: SearchTeamMessagesDto,
        ctx: UserContext,
    ): Promise<SearchMessagesResponseDto> {
        const isMember = await this.channelMemberRepository.existsByTeam(teamId, ctx.userId);
        if (!isMember) throw new ForbiddenException('팀 접근 권한이 없습니다');

        const memberships = await this.channelMemberRepository.findMembersByTeam(teamId, ctx.userId);
        let accessibleChannelIds = memberships.map((m) => m.channelId);

        if (dto.channelId !== undefined) {
            if (!accessibleChannelIds.includes(dto.channelId)) {
                throw new ForbiddenException('채널 접근 권한이 없습니다');
            }
            accessibleChannelIds = [dto.channelId];
        }

        if (accessibleChannelIds.length === 0) {
            return { messages: [], nextCursor: null };
        }

        const { hits, nextCursor } = await this.elasticsearchService.searchTeamMessages({
            teamId,
            accessibleChannelIds,
            q: dto.q,
            authorId: dto.authorId,
            type: dto.type,
            hasFile: dto.hasFile,
            before: dto.before,
            limit: dto.limit ?? 50,
        });

        return { messages: hits, nextCursor };
    }

    /**
     * 메시지 내용을 수정한다. 내용이 동일하면 저장 없이 현재 도큐먼트를 반환한다.
     * 수정 이력은 `editHistory`에 누적된다.
     * projectId가 있으면 Elasticsearch도 비동기로 업데이트한다.
     * 성공 시 `chat:{channelId}` 룸에 `message:edited` 이벤트를 emit한다.
     * ADMIN은 타인 메시지도 수정할 수 있다.
     *
     * @param ctx - 메시지·사용자·역할 컨텍스트
     * @param dto - 새 메시지 내용
     * @returns 수정된 메시지 도큐먼트
     */
    async editMessage(ctx: MessageUserRoleContext, dto: EditMessageDto) {
        const message = await this.findAndVerifyMessage(ctx, '본인 메시지만 수정할 수 있습니다');

        if (message.content === dto.content) {
            return message;
        }

        message.editHistory.push({ content: message.content, editedAt: new Date() });
        message.content = dto.content;
        message.isEdited = true;
        const updated = await message.save();
        if (updated.projectId) {
            this.elasticsearchService.updateMessage(ctx.messageId, dto.content).catch((err) =>
                this.logger.error(`ES updateMessage 실패 [messageId=${ctx.messageId}]`, err),
            );
        }

        this.chatGateway.server
            ?.to(`chat:${ctx.channelId}`)
            .emit('message:edited', {
                messageId: ctx.messageId,
                content: updated.content,
                editedAt: (updated).updatedAt?.toISOString(),
            });

        return updated;
    }

    /**
     * 메시지를 삭제한다.
     * projectId가 있으면 Elasticsearch에서도 비동기로 제거한다.
     * 성공 시 `chat:{channelId}` 룸에 `message:deleted` 이벤트를 emit한다.
     * ADMIN은 타인 메시지도 삭제할 수 있다.
     *
     * @param ctx - 메시지·사용자·역할 컨텍스트
     * @returns 삭제된 채널 ID와 메시지 ID
     */
    async deleteMessage(ctx: MessageUserRoleContext) {
        const message = await this.findAndVerifyMessage(ctx, '본인 메시지만 삭제할 수 있습니다');

        await this.messageRepository.deleteById(ctx.messageId);
        if (message.projectId) {
            this.elasticsearchService.deleteMessage(ctx.messageId).catch((err) =>
                this.logger.error(`ES deleteMessage 실패 [messageId=${ctx.messageId}]`, err),
            );
        }

        this.chatGateway.server
            ?.to(`chat:${ctx.channelId}`)
            .emit('message:deleted', { messageId: ctx.messageId });

        return { channelId: message.channelId, messageId: ctx.messageId };
    }

    /**
     * 메시지를 고정한다. 이미 고정된 메시지이면 `BadRequestException`을 던진다.
     * projectId가 있으면 Elasticsearch에서도 비동기로 핀 상태를 업데이트한다.
     * 성공 시 `chat:{channelId}` 룸에 `message:pinned` 이벤트를 emit한다.
     * ADMIN은 타인 메시지도 고정할 수 있다.
     *
     * @param ctx - 메시지·사용자·역할 컨텍스트
     * @returns 업데이트된 메시지 도큐먼트
     * @throws BadRequestException 이미 고정된 메시지인 경우
     */
    async pinMessage(ctx: MessageUserRoleContext) {
        const message = await this.findAndVerifyMessage(ctx, '본인 메시지만 고정할 수 있습니다');
        if (message.isPinned) throw new BadRequestException('이미 고정된 메시지입니다');

        message.isPinned = true;
        const updated = await message.save();

        if (updated.projectId) {
            this.elasticsearchService.updatePinStatus(ctx.messageId, true).catch((err) =>
                this.logger.error(`ES updatePinStatus 실패 [messageId=${ctx.messageId}]`, err),
            );
        }

        this.chatGateway.server
            ?.to(`chat:${ctx.channelId}`)
            .emit('message:pinned', { messageId: ctx.messageId, channelId: ctx.channelId });

        return updated;
    }

    /**
     * 메시지 고정을 해제한다. 고정되지 않은 메시지이면 `BadRequestException`을 던진다.
     * projectId가 있으면 Elasticsearch에서도 비동기로 핀 상태를 업데이트한다.
     * 성공 시 `chat:{channelId}` 룸에 `message:unpinned` 이벤트를 emit한다.
     * ADMIN은 타인 메시지도 고정 해제할 수 있다.
     *
     * @param ctx - 메시지·사용자·역할 컨텍스트
     * @throws BadRequestException 고정되지 않은 메시지인 경우
     */
    async unpinMessage(ctx: MessageUserRoleContext) {
        const message = await this.findAndVerifyMessage(ctx, '본인 메시지만 고정 해제할 수 있습니다');
        if (!message.isPinned) throw new BadRequestException('고정되지 않은 메시지입니다');

        message.isPinned = false;
        const updated = await message.save();

        if (updated.projectId) {
            this.elasticsearchService.updatePinStatus(ctx.messageId, false).catch((err) =>
                this.logger.error(`ES updatePinStatus 실패 [messageId=${ctx.messageId}]`, err),
            );
        }

        this.chatGateway.server
            ?.to(`chat:${ctx.channelId}`)
            .emit('message:unpinned', { messageId: ctx.messageId, channelId: ctx.channelId });
    }

    /**
     * 메시지에 이모지 반응을 추가한다.
     * 이미 같은 이모지로 반응한 경우 무시한다(이벤트 emit 안 함).
     * 성공 시 `chat:{channelId}` 룸에 `message:reaction:added` 이벤트를 emit한다.
     *
     * @param ctx - 채널·사용자 컨텍스트
     * @param messageId - 대상 메시지의 MongoDB ObjectId
     * @param emoji - 추가할 이모지
     * @throws NotFoundException 메시지가 존재하지 않는 경우
     */
    async addReaction(ctx: ChannelUserContext, messageId: string, emoji: string): Promise<void> {
        await this.checkMembership(ctx.channelId, ctx.userId);
        const count = await this.messageRepository.addReaction(ctx.channelId, messageId, emoji, ctx.userId);
        if (count === null) throw new NotFoundException('메시지를 찾을 수 없습니다');
        if (count === -1) return; // 이미 반응한 경우 무시

        this.chatGateway.server
            ?.to(`chat:${ctx.channelId}`)
            .emit('message:reaction:added', {
                messageId,
                channelId: ctx.channelId,
                emoji,
                userId: ctx.userId,
                count,
            });
    }

    /**
     * 메시지에서 이모지 반응을 제거한다.
     * 반응이 없었던 경우 무시한다(이벤트 emit 안 함).
     * 성공 시 `chat:{channelId}` 룸에 `message:reaction:removed` 이벤트를 emit한다.
     *
     * @param ctx - 채널·사용자 컨텍스트
     * @param messageId - 대상 메시지의 MongoDB ObjectId
     * @param emoji - 제거할 이모지
     * @throws NotFoundException 메시지가 존재하지 않는 경우
     */
    async removeReaction(ctx: ChannelUserContext, messageId: string, emoji: string): Promise<void> {
        await this.checkMembership(ctx.channelId, ctx.userId);
        const count = await this.messageRepository.removeReaction(ctx.channelId, messageId, emoji, ctx.userId);
        if (count === null) throw new NotFoundException('메시지를 찾을 수 없습니다');
        if (count === -1) return; // 반응이 없었던 경우 무시

        this.chatGateway.server
            ?.to(`chat:${ctx.channelId}`)
            .emit('message:reaction:removed', {
                messageId,
                channelId: ctx.channelId,
                emoji,
                userId: ctx.userId,
                count,
            });
    }

    /**
     * 채널에 고정된 메시지를 최신순으로 최대 100개 조회한다.
     *
     * @param ctx - 채널·사용자 컨텍스트
     * @returns 고정 메시지 배열 (reactions는 나의 반응 여부 포함)
     */
    async readChannel(ctx: ChannelUserContext, lastReadMessageId: string): Promise<void> {
        await this.checkMembership(ctx.channelId, ctx.userId);
        const oid = new Types.ObjectId(lastReadMessageId);
        await this.channelMemberRepository.updateLastRead(ctx.channelId, ctx.userId, oid);
        const unreadCount = await this.messageRepository.countUnread(ctx.channelId, oid);
        this.chatGateway.server
            ?.to(`user:${ctx.userId}`)
            .emit('channel:unread:updated', { channelId: ctx.channelId, unreadCount });
    }

    async getTeamUnread(teamId: number, userId: number): Promise<Array<{ channelId: number; unreadCount: number }>> {
        const memberships = await this.channelMemberRepository.findMembersByTeam(teamId, userId);
        if (memberships.length === 0) {
            return [];
        }
        const unreadCounts = await this.messageRepository.countUnreadForChannels(memberships);
        return memberships.map(({ channelId }) => ({
            channelId,
            unreadCount: unreadCounts.get(channelId) ?? 0,
        }));
    }

    async getPinnedMessages(ctx: ChannelUserContext) {
        await this.checkMembership(ctx.channelId, ctx.userId);
        const rows = await this.messageRepository.findPinnedMessages(ctx.channelId);
        return rows.map(row => this.toMessageResponse(row, ctx.userId));
    }

    /**
     * 시스템 메시지를 직접 저장한다. GitHub 이슈 결과 등 서비스 내부에서 생성하는 알림에 사용한다.
     * `authorId`는 0(`SYSTEM_AUTHOR_ID`)으로 고정된다.
     *
     * @param teamId - 팀 ID
     * @param channelId - 채널 ID
     * @param content - 메시지 내용
     * @param projectId - 프로젝트 ID (팀 채널이면 null)
     * @returns 저장된 메시지 도큐먼트
     */
    async saveSystemMessage(
        teamId: number,
        channelId: number,
        content: string,
        projectId: number | null = null,
    ) {
        return this.messageRepository.createSystemMessage(teamId, channelId, content, projectId, SYSTEM_AUTHOR_ID);
    }

    /**
     * `MessageRow`를 응답 DTO 형태로 변환한다.
     * `reactions`의 `myReaction` 필드는 요청자가 해당 이모지로 반응했는지 여부다.
     *
     * @param row - DB에서 조회한 메시지 row
     * @param userId - 응답을 받을 요청자 ID
     * @returns 응답 형태의 메시지 객체
     */
    private toMessageResponse(row: MessageRow, userId: number) {
        return {
            ...row,
            reactions: (row.reactions ?? []).map(r => ({
                emoji: r.emoji,
                count: r.userIds.length,
                myReaction: r.userIds.includes(userId),
            })),
        };
    }

    /**
     * @param role - 사용자 역할 문자열
     * @returns ADMIN 역할이면 `true`
     */
    private isAdmin(role: string): boolean {
        return role === (UserRole.ADMIN as string);
    }

    /**
     * base64 인코딩된 fileId에서 messageId를 추출한다.
     * fileId는 `{ messageId: string }` 형태의 JSON을 base64로 인코딩한 값이다.
     *
     * @param fileId - base64 인코딩된 파일 식별자
     * @returns 추출된 messageId (MongoDB ObjectId 문자열)
     * @throws BadRequestException fileId 형식이 유효하지 않은 경우
     */
    private decodeFileId(fileId: string): string {
        try {
            const parsed = JSON.parse(Buffer.from(fileId, 'base64url').toString('utf8')) as {
                messageId?: unknown;
            };
            if (typeof parsed.messageId !== 'string' || !Types.ObjectId.isValid(parsed.messageId)) {
                throw new BadRequestException('유효하지 않은 fileId입니다');
            }
            return parsed.messageId;
        } catch {
            throw new BadRequestException('유효하지 않은 fileId입니다');
        }
    }

    /**
     * 채널 멤버십, 메시지 존재 여부, 채널 소속, 소유권(또는 ADMIN 여부)을 순서대로 검증한다.
     * 소유권 검증에서 ADMIN은 타인 메시지에도 접근 가능하다.
     *
     * @param ctx - 메시지·사용자·역할 컨텍스트
     * @param forbiddenMessage - 소유권 위반 시 사용할 오류 메시지
     * @returns 검증을 통과한 메시지 도큐먼트
     * @throws ForbiddenException 멤버가 아니거나, 다른 채널의 메시지이거나, 소유권 없는 경우
     * @throws NotFoundException 메시지가 존재하지 않는 경우
     */
    private async findAndVerifyMessage(
        ctx: MessageUserRoleContext,
        forbiddenMessage: string,
    ): Promise<MessageDocument> {
        await this.checkMembership(ctx.channelId, ctx.userId);
        const message = await this.messageRepository.findById(ctx.messageId);
        if (!message) throw new NotFoundException('메시지를 찾을 수 없습니다');
        if (message.channelId !== ctx.channelId) throw new ForbiddenException('해당 채널의 메시지가 아닙니다');
        if (message.authorId !== ctx.userId && !this.isAdmin(ctx.userRole)) throw new ForbiddenException(forbiddenMessage);
        return message;
    }

    /**
     * 업로더 ID 목록에 대한 표시 이름을 user-service에서 조회한다.
     * `SYSTEM_AUTHOR_ID(0)` 이하이면 user-service 호출 없이 'System'으로 처리한다.
     *
     * @param items - uploaderId 필드를 포함하는 아이템 배열
     * @returns uploaderId → 표시 이름 매핑
     */
    private async loadUploaderNames(items: Array<{ uploaderId: number }>): Promise<Map<number, string>> {
        const uniqueUploaderIds = [...new Set(items.map((item) => item.uploaderId))];
        const systemIds = uniqueUploaderIds.filter((id) => id <= SYSTEM_AUTHOR_ID);
        const realIds = uniqueUploaderIds.filter((id) => id > SYSTEM_AUTHOR_ID);

        const names = await this.userClient.getDisplayNames(realIds);
        for (const systemId of systemIds) {
            names.set(systemId, SYSTEM_AUTHOR_NAME);
        }

        return names;
    }
}
