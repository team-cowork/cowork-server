import { ForbiddenException, Injectable, Logger, NotFoundException } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import { Message } from './schema/message.schema';
import { ChannelMember } from './schema/channel-member.schema';
import { EditMessageDto } from './dto/edit-message.dto';
import { UserRole } from '../common/enum/user-role.enum';
import { ElasticsearchService } from '../search/elasticsearch.service';

const SYSTEM_AUTHOR_ID = 0;

@Injectable()
export class ChatService {
    private readonly logger = new Logger(ChatService.name);

    constructor(
        @InjectModel(Message.name) private readonly messageModel: Model<Message>,
        @InjectModel(ChannelMember.name) private readonly memberModel: Model<ChannelMember>,
        private readonly elasticsearchService: ElasticsearchService,
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

    async editMessage(messageId: string, userId: number, dto: EditMessageDto, userRole: string) {
        const message = await this.messageModel.findById(messageId);
        if (!message) throw new NotFoundException('메시지를 찾을 수 없습니다');
        if (message.authorId !== userId && !this.isAdmin(userRole)) {
            throw new ForbiddenException('본인 메시지만 수정할 수 있습니다');
        }

        if (message.content === dto.content) return message;
        message.editHistory.push({ content: message.content, editedAt: new Date() });
        message.content = dto.content;
        message.isEdited = true;
        const saved = await message.save();
        this.elasticsearchService.updateMessage(messageId, dto.content).catch((err) =>
            this.logger.error(`ES 메시지 수정 동기화 실패 id=${messageId}`, err),
        );
        return saved;
    }

    async deleteMessage(messageId: string, userId: number, userRole: string) {
        const message = await this.messageModel.findById(messageId);
        if (!message) throw new NotFoundException('메시지를 찾을 수 없습니다');
        if (message.authorId !== userId && !this.isAdmin(userRole)) {
            throw new ForbiddenException('본인 메시지만 삭제할 수 있습니다');
        }

        await this.messageModel.deleteOne({ _id: messageId });
        this.elasticsearchService.deleteMessage(messageId).catch((err) =>
            this.logger.error(`ES 메시지 삭제 동기화 실패 id=${messageId}`, err),
        );
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
