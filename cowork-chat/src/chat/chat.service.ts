import {
    BadRequestException,
    ForbiddenException,
    forwardRef,
    Inject,
    Injectable,
    Logger,
    NotFoundException,
} from '@nestjs/common';
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
import { SearchMessagesResponseDto } from './dto/search-message-response.dto';
import { FileListQueryDto, FileListResponseDto } from './dto/file-list.dto';
import { MessageRepository } from './repository/message.repository';
import { ChannelMemberRepository } from './repository/channel-member.repository';
import {
    ChannelUserContext,
    ChannelUserRoleContext,
    MessageUserRoleContext,
    UserContext,
} from './dto/context';

const SYSTEM_AUTHOR_ID = 0;
const SYSTEM_AUTHOR_NAME = 'System';
const FILE_SHARE_VIEW_TYPE = 'FILE_SHARE';

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
        @Inject(forwardRef(() => ChatGateway))
        private readonly chatGateway: ChatGateway,
    ) {}

    async isMember(channelId: number, userId: number): Promise<boolean> {
        return this.channelMemberRepository.exists(channelId, userId);
    }

    async checkMembership(channelId: number, userId: number): Promise<void> {
        const isMember = await this.channelMemberRepository.exists(channelId, userId);
        if (!isMember) throw new ForbiddenException('채널 접근 권한이 없습니다');
    }

    async checkMembershipAndGetTeamId(channelId: number, userId: number): Promise<number> {
        const teamId = await this.channelMemberRepository.findTeamIdByChannelAndUser(channelId, userId);
        if (teamId === null) throw new ForbiddenException('채널 접근 권한이 없습니다');
        return teamId;
    }

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

    async confirmFileUpload(ctx: ChannelUserContext, dto: ConfirmFileUploadRequestDto): Promise<string> {
        await this.checkMembership(ctx.channelId, ctx.userId);
        return this.minioService.confirmUpload(ctx.channelId, ctx.userId, dto.objectKey);
    }

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

    async sendMessage(ctx: ChannelUserRoleContext, dto: SendMessageDto): Promise<void> {
        await this.checkMembership(ctx.channelId, ctx.userId);
        await this.chatMessageProducer.sendMessage(ctx.channelId, dto, ctx.userId, ctx.userRole);
    }

    async handleSlashCommand(ctx: ChannelUserContext, dto: SlashCommandDto): Promise<void> {
        if (dto.command !== SlashCommand.GITHUB_ISSUE_CREATE) {
            throw new BadRequestException('지원하지 않는 슬래시 커맨드입니다');
        }
        await this.publishGithubIssueCreateCommand(ctx, dto.payload);
    }

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

    async getMessages(ctx: ChannelUserContext, before?: string) {
        await this.checkMembership(ctx.channelId, ctx.userId);
        return this.messageRepository.findMessages(ctx.channelId, before);
    }

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
            void this.elasticsearchService.updateMessage(ctx.messageId, dto.content);
        }

        this.chatGateway.server
            ?.to(`chat:${ctx.channelId}`)
            .emit('message:edited', {
                messageId: ctx.messageId,
                content: updated.content,
                editedAt: (updated as MessageDocument).updatedAt?.toISOString(),
            });

        return updated;
    }

    async deleteMessage(ctx: MessageUserRoleContext) {
        const message = await this.findAndVerifyMessage(ctx, '본인 메시지만 삭제할 수 있습니다');

        await this.messageRepository.deleteById(ctx.messageId);
        if (message.projectId) {
            void this.elasticsearchService.deleteMessage(ctx.messageId);
        }

        this.chatGateway.server
            ?.to(`chat:${ctx.channelId}`)
            .emit('message:deleted', { messageId: ctx.messageId });

        return { channelId: message.channelId, messageId: ctx.messageId };
    }

    async saveSystemMessage(
        teamId: number,
        channelId: number,
        content: string,
        projectId: number | null = null,
    ) {
        return this.messageRepository.createSystemMessage(teamId, channelId, content, projectId, SYSTEM_AUTHOR_ID);
    }

    private isAdmin(role: string): boolean {
        return role === UserRole.ADMIN;
    }

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

    private async loadUploaderNames(items: Array<{ uploaderId: number }>): Promise<Map<number, string>> {
        const uniqueUploaderIds = [...new Set(items.map((item) => item.uploaderId))];
        const entries = await Promise.all(
            uniqueUploaderIds.map(async (uploaderId) => {
                if (uploaderId <= SYSTEM_AUTHOR_ID) return [uploaderId, SYSTEM_AUTHOR_NAME] as const;
                const name = await this.userClient.getDisplayName(uploaderId);
                return [uploaderId, name] as const;
            }),
        );

        return new Map(entries);
    }
}
