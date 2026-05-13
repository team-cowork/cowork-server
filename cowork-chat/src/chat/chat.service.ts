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
import { MinioService } from '../storage/minio.service';
import { ChatMessageProducer } from './kafka/chat-message.producer';
import { GithubIssueProducer } from './kafka/github-issue.producer';
import { ProjectClient } from './service/project.client';
import { ChannelClient } from './service/channel.client';
import { UserClient } from './service/user.client';
import { ChatGateway } from './chat.gateway';
import { SendMessageDto } from './dto/send-message.dto';
import { CreateFileUploadUrlRequestDto, CreateFileUploadUrlResponseDto } from './dto/create-file-upload-url.dto';
import { CreateGithubIssueDto } from './dto/create-github-issue.dto';
import { SlashCommand, SlashCommandDto } from './dto/slash-command.dto';
import { SearchMessagesDto } from './dto/search-messages.dto';
import { SearchMessagesResponseDto } from './dto/search-message-response.dto';
import { FileListResponseDto } from './dto/file-list.dto';

const SYSTEM_AUTHOR_ID = 0;
const FILE_SHARE_VIEW_TYPE = 'FILE_SHARE';
const FILE_ATTACHMENT_LIMIT = 100;

type FileAttachmentRow = {
    messageId: string;
    fileName: string;
    fileSize: number;
    fileUrl: string;
    mimeType: string;
    uploaderId: number;
    uploadedAt: string;
    attachmentIndex: number;
};

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
        private readonly channelClient: ChannelClient,
        private readonly userClient: UserClient,
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

    async getFileList(
        channelId: number,
        userId: number,
        before?: string,
        limit = 20,
    ): Promise<FileListResponseDto> {
        await this.checkMembership(channelId, userId);

        const channel = await this.channelClient.getChannel(channelId, userId);
        if (channel.viewType !== FILE_SHARE_VIEW_TYPE) {
            throw new BadRequestException('FILE_SHARE 채널에서만 파일 목록을 조회할 수 있습니다');
        }

        const { items, nextCursor } = await this.queryFileAttachments(channelId, before, limit);
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
        if (message.channelId !== channelId) {
            throw new ForbiddenException('해당 채널의 메시지가 아닙니다');
        }
        if (message.authorId !== userId && !this.isAdmin(userRole)) {
            throw new ForbiddenException('본인 메시지만 수정할 수 있습니다');
        }

        if (message.content === dto.content) {
            return message;
        }

        message.editHistory.push({ content: message.content, editedAt: new Date() });
        message.content = dto.content;
        message.isEdited = true;
        const updated = await message.save();
        if (updated.projectId) {
            void this.elasticsearchService.updateMessage(messageId, dto.content);
        }

        this.chatGateway.server
            ?.to(`chat:${channelId}`)
            .emit('message:edited', {
                messageId,
                content: updated.content,
                editedAt: (updated as MessageDocument).updatedAt?.toISOString(),
            });

        return updated;
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

    private async queryFileAttachments(
        channelId: number,
        before: string | undefined,
        limit: number,
    ): Promise<{ items: FileAttachmentRow[]; nextCursor: string | null }> {
        const safeLimit = Math.min(Math.max(limit, 1), FILE_ATTACHMENT_LIMIT);
        const cursorMatch = before ? this.buildFileCursorMatch(before) : null;

        const pipeline: any[] = [
            {
                $match: {
                    channelId,
                    type: 'FILE',
                    'attachments.0': { $exists: true },
                },
            },
            {
                $unwind: {
                    path: '$attachments',
                    includeArrayIndex: 'attachmentIndex',
                },
            },
            ...(cursorMatch ? [{ $match: cursorMatch }] : []),
            {
                $sort: {
                    createdAt: -1,
                    _id: -1,
                    attachmentIndex: -1,
                },
            },
            { $limit: safeLimit + 1 },
            {
                $project: {
                    _id: 1,
                    authorId: 1,
                    createdAt: 1,
                    attachmentIndex: 1,
                    attachment: '$attachments',
                },
            },
        ];

        const rows = await this.messageModel.aggregate(pipeline);
        const hasNext = rows.length > safeLimit;
        const pageRows = rows.slice(0, safeLimit);
        const items: FileAttachmentRow[] = pageRows.map((row: any) => ({
            messageId: row._id.toString(),
            fileName: row.attachment.name,
            fileSize: row.attachment.size,
            fileUrl: row.attachment.url,
            mimeType: row.attachment.mimeType,
            uploaderId: row.authorId,
            uploadedAt: row.createdAt.toISOString(),
            attachmentIndex: row.attachmentIndex ?? 0,
        }));

        return {
            items,
            nextCursor: hasNext && pageRows.length > 0
                ? this.encodeFileCursor(pageRows.at(-1))
                : null,
        };
    }

    private buildFileCursorMatch(before: string): Record<string, unknown> | null {
        const cursor = this.decodeFileCursor(before);
        if (!cursor) {
            return null;
        }

        return {
            $or: [
                { createdAt: { $lt: new Date(cursor.uploadedAt) } },
                {
                    createdAt: new Date(cursor.uploadedAt),
                    _id: { $lt: new Types.ObjectId(cursor.messageId) },
                },
                {
                    createdAt: new Date(cursor.uploadedAt),
                    _id: new Types.ObjectId(cursor.messageId),
                    attachmentIndex: { $lt: cursor.attachmentIndex },
                },
            ],
        };
    }

    private encodeFileCursor(row: any): string | null {
        if (!row) {
            return null;
        }

        return Buffer.from(JSON.stringify({
            uploadedAt: row.createdAt.toISOString(),
            messageId: row._id.toString(),
            attachmentIndex: row.attachmentIndex ?? 0,
        })).toString('base64');
    }

    private decodeFileCursor(before: string): { uploadedAt: string; messageId: string; attachmentIndex: number } | null {
        try {
            const parsed = JSON.parse(Buffer.from(before, 'base64').toString('utf8')) as {
                uploadedAt?: unknown;
                messageId?: unknown;
                attachmentIndex?: unknown;
            };

            if (
                typeof parsed.uploadedAt !== 'string' ||
                typeof parsed.messageId !== 'string' ||
                typeof parsed.attachmentIndex !== 'number' ||
                Number.isNaN(Date.parse(parsed.uploadedAt)) ||
                !Types.ObjectId.isValid(parsed.messageId)
            ) {
                return null;
            }

            return {
                uploadedAt: parsed.uploadedAt,
                messageId: parsed.messageId,
                attachmentIndex: parsed.attachmentIndex,
            };
        } catch {
            return null;
        }
    }

    private async loadUploaderNames(items: Array<{ uploaderId: number }>): Promise<Map<number, string>> {
        const uniqueUploaderIds = [...new Set(items.map((item) => item.uploaderId))];
        const entries = await Promise.all(
            uniqueUploaderIds.map(async (uploaderId) => [uploaderId, await this.userClient.getDisplayName(uploaderId)] as const),
        );

        return new Map(entries);
    }
}
