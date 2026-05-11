import {
    BadRequestException,
    ForbiddenException,
    forwardRef,
    Inject,
    Injectable,
    Logger,
    NotFoundException,
} from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import { Message, MessageDocument } from './schema/message.schema';
import { ChannelMember } from './schema/channel-member.schema';
import { EditMessageDto } from './dto/edit-message.dto';
import { UserRole } from '../common/enum/user-role.enum';
import { ElasticsearchService } from '../search/elasticsearch.service';
import { MinioService, PresignedUpload } from '../storage/minio.service';
import { ChatMessageProducer } from './kafka/chat-message.producer';
import { GithubIssueProducer } from './kafka/github-issue.producer';
import { ProjectClient } from './service/project.client';
import { ChatGateway } from './chat.gateway';
import { SendMessageDto } from './dto/send-message.dto';
import { CreateFileUploadUrlRequestDto, CreateFileUploadUrlResponseDto } from './dto/create-file-upload-url.dto';
import { CreateGithubIssueDto } from './dto/create-github-issue.dto';
import { SlashCommand, SlashCommandDto } from './dto/slash-command.dto';
import { SearchMessagesDto } from './dto/search-messages.dto';
import { SearchMessagesResponseDto } from './dto/search-message-response.dto';

const SYSTEM_AUTHOR_ID = 0;

@Injectable()
export class ChatService {
    private readonly logger = new Logger(ChatService.name);

    constructor(
        @InjectModel(Message.name) private readonly messageModel: Model<Message>,
        @InjectModel(ChannelMember.name) private readonly memberModel: Model<ChannelMember>,
        private readonly elasticsearchService: ElasticsearchService,
        private readonly minioService: MinioService,
        private readonly chatMessageProducer: ChatMessageProducer,
        private readonly githubIssueProducer: GithubIssueProducer,
        private readonly projectClient: ProjectClient,
        @Inject(forwardRef(() => ChatGateway))
        private readonly chatGateway: ChatGateway,
    ) {}

    async isMember(channelId: number, userId: number): Promise<boolean> {
        const member = await this.memberModel.exists({ channelId, userId });
        return member !== null;
    }

    async checkMembership(channelId: number, userId: number): Promise<void> {
        const member = await this.memberModel.exists({ channelId, userId });
        if (!member) throw new ForbiddenException('채널 접근 권한이 없습니다');
    }

    async checkMembershipAndGetTeamId(channelId: number, userId: number): Promise<number> {
        const member = await this.memberModel.findOne({ channelId, userId }, { teamId: 1 });
        if (!member) throw new ForbiddenException('채널 접근 권한이 없습니다');
        return member.teamId;
    }

    async createFileUploadUrl(
        channelId: number,
        dto: CreateFileUploadUrlRequestDto,
        userId: number,
    ): Promise<CreateFileUploadUrlResponseDto> {
        await this.checkMembership(channelId, userId);
        const upload = await this.minioService.createPresignedUpload({
            channelId,
            userId,
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

    async confirmFileUpload(channelId: number, objectKey: string, userId: number): Promise<string> {
        await this.checkMembership(channelId, userId);
        return this.minioService.confirmUpload(channelId, userId, objectKey);
    }

    async sendMessage(
        channelId: number,
        dto: SendMessageDto,
        userId: number,
        userRole: string,
    ): Promise<void> {
        await this.checkMembership(channelId, userId);
        await this.chatMessageProducer.sendMessage(channelId, dto, userId, userRole);
    }

    async handleSlashCommand(
        channelId: number,
        dto: SlashCommandDto,
        userId: number,
    ): Promise<void> {
        if (dto.command !== SlashCommand.GITHUB_ISSUE_CREATE) {
            throw new BadRequestException('지원하지 않는 슬래시 커맨드입니다');
        }
        await this.publishGithubIssueCreateCommand(channelId, dto.payload, userId);
    }

    async publishGithubIssueCreateCommand(
        channelId: number,
        dto: CreateGithubIssueDto,
        userId: number,
    ): Promise<void> {
        const channelTeamId = await this.checkMembershipAndGetTeamId(channelId, userId);
        const repoInfo = await this.projectClient.getGithubRepoInfo(dto.projectId);
        if (!repoInfo) {
            throw new BadRequestException('프로젝트 GitHub 레포지토리 정보를 찾을 수 없습니다');
        }
        if (channelTeamId !== repoInfo.teamId) {
            throw new ForbiddenException('해당 프로젝트는 이 채널의 팀에 속하지 않습니다');
        }

        await this.githubIssueProducer.send({
            channelId,
            teamId: repoInfo.teamId,
            projectId: dto.projectId,
            owner: repoInfo.owner,
            repo: repoInfo.repo,
            title: dto.title,
            body: dto.body,
            requesterId: userId,
        });
    }

    async getMessages(channelId: number, before?: string) {
        const query: Record<string, unknown> = { channelId };
        if (before) {
            query['_id'] = { $lt: new Types.ObjectId(before) };
        }

        return this.messageModel
            .aggregate([
                { $match: query },
                { $sort: { _id: -1 } },
                { $limit: 100 },
                {
                    $lookup: {
                        from: this.messageModel.collection.name,
                        localField: 'parentMessageId',
                        foreignField: '_id',
                        as: 'mentionedMessage',
                        pipeline: [
                            {
                                $project: {
                                    _id: 1,
                                    authorId: 1,
                                    content: 1,
                                    type: 1,
                                    createdAt: 1,
                                },
                            },
                        ],
                    },
                },
                {
                    $addFields: {
                        mentionedMessage: { $arrayElemAt: ['$mentionedMessage', 0] },
                    },
                },
            ]);
    }

    async searchProjectMessages(
        projectId: number,
        dto: SearchMessagesDto,
        userId: number,
    ): Promise<SearchMessagesResponseDto> {
        const isMember = await this.projectClient.isMember(projectId, userId);
        if (!isMember) {
            throw new ForbiddenException('프로젝트 접근 권한이 없습니다');
        }

        const memberships = await this.memberModel.find({ userId }, { channelId: 1 }).lean();
        const accessibleChannelIds = memberships.map((membership) => membership.channelId);

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

    async editMessage(channelId: number, messageId: string, userId: number, dto: EditMessageDto, userRole: string) {
        await this.checkMembership(channelId, userId);
        const message = await this.messageModel.findById(messageId);
        if (!message) throw new NotFoundException('메시지를 찾을 수 없습니다');
        if (message.authorId !== userId && !this.isAdmin(userRole)) {
            throw new ForbiddenException('본인 메시지만 수정할 수 있습니다');
        }

        if (message.content !== dto.content) {
            message.editHistory.push({ content: message.content, editedAt: new Date() });
            message.content = dto.content;
            message.isEdited = true;
            await message.save();
            if (message.projectId) {
                void this.elasticsearchService.updateMessage(messageId, dto.content);
            }
        }

        this.chatGateway.server
            ?.to(`chat:${channelId}`)
            .emit('message:edited', {
                messageId,
                content: message.content,
                editedAt: (message as MessageDocument).updatedAt?.toISOString() ?? new Date().toISOString(),
            });

        return message;
    }

    async deleteMessage(channelId: number, messageId: string, userId: number, userRole: string) {
        await this.checkMembership(channelId, userId);
        const message = await this.messageModel.findById(messageId);
        if (!message) throw new NotFoundException('메시지를 찾을 수 없습니다');
        if (message.authorId !== userId && !this.isAdmin(userRole)) {
            throw new ForbiddenException('본인 메시지만 삭제할 수 있습니다');
        }

        await this.messageModel.deleteOne({ _id: messageId });
        if (message.projectId) {
            void this.elasticsearchService.deleteMessage(messageId);
        }

        this.chatGateway.server
            ?.to(`chat:${channelId}`)
            .emit('message:deleted', { messageId });

        return { channelId: message.channelId, messageId };
    }

    async saveSystemMessage(
        teamId: number,
        channelId: number,
        content: string,
        projectId: number | null = null,
    ) {
        return this.messageModel.create({
            teamId,
            projectId,
            channelId,
            authorId: SYSTEM_AUTHOR_ID,
            content,
            type: 'SYSTEM',
            attachments: [],
            mentions: [],
            clientMessageId: undefined,
            notificationStatus: 'SENT',
        });
    }

    private isAdmin(role: string): boolean {
        return role === UserRole.ADMIN;
    }
}
