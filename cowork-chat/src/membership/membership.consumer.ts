import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import { Server } from 'socket.io';
import { ChannelMember } from '../chat/schema/channel-member.schema';
import { ChannelMemberEvent } from './event/membership.event';
import { getRequiredCsvConfig } from '../common/config/config.util';

@Injectable()
export class MembershipConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(MembershipConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(
        @InjectModel(ChannelMember.name) private readonly memberModel: Model<ChannelMember>,
        private readonly configService: ConfigService,
    ) {}

    setSocketServer(io: Server) {
        this.io = io;
    }

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-membership',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.consumer = kafka.consumer({ groupId: 'cowork-chat-membership' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: 'channel.member.event', fromBeginning: false });

        void this.consumer
            .run({
                eachMessage: async ({ message }) => {
                    if (!message.value) return;
                    try {
                        const event: ChannelMemberEvent = JSON.parse(message.value.toString());
                        await this.handleEvent(event);
                    } catch (err) {
                        this.logger.error('멤버십 Kafka 메시지 처리 중 예외 발생', err);
                        if (!(err instanceof SyntaxError)) throw err;
                    }
                },
            })
            .catch((err) => this.logger.error('멤버십 Kafka consumer 실행 실패', err));
        this.logger.log('Kafka consumer started: channel.member.event');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleEvent(event: ChannelMemberEvent): Promise<void> {
        const { eventType, channelId, teamId, userId, role } = event;

        try {
            if (eventType === 'JOIN') {
                await this.memberModel.updateOne(
                    { channelId, userId },
                    { $set: { teamId, role } },
                    { upsert: true },
                );
                this.io?.to(`chat:${channelId}`).emit('member:joined', { channelId, teamId, userId, role });
            } else if (eventType === 'LEAVE') {
                await this.memberModel.deleteOne({ channelId, userId });
                this.io?.to(`chat:${channelId}`).emit('member:left', { channelId, teamId, userId });
            } else if (eventType === 'ROLE_CHANGE') {
                await this.memberModel.updateOne({ channelId, userId }, { $set: { teamId, role } });
                this.io?.to(`chat:${channelId}`).emit('member:role:updated', { channelId, teamId, userId, role });
            }
        } catch (err) {
            this.logger.error(`멤버십 이벤트 처리 실패 [${eventType}]`, err);
            throw err;
        }
    }
}
