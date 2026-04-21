import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { Kafka, Consumer } from 'kafkajs';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import { ChannelMember } from '../chat/schema/channel-member.schema';

interface ChannelMemberEvent {
    eventType: 'JOIN' | 'LEAVE' | 'ROLE_CHANGE';
    channelId: number;
    userId: number;
    role: string;
    occurredAt: string;
}

@Injectable()
export class MembershipConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(MembershipConsumer.name);
    private readonly kafka = new Kafka({
        clientId: 'cowork-chat-membership',
        brokers: [(process.env.KAFKA_BOOTSTRAP_SERVERS ?? 'localhost:9092')],
    });
    private consumer!: Consumer;

    constructor(
        @InjectModel(ChannelMember.name) private readonly memberModel: Model<ChannelMember>,
    ) {}

    async onModuleInit() {
        this.consumer = this.kafka.consumer({ groupId: 'cowork-chat-membership' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: 'channel.member.event', fromBeginning: false });

        await this.consumer.run({
            eachMessage: async ({ message }) => {
                if (!message.value) return;
                const event: ChannelMemberEvent = JSON.parse(message.value.toString());
                await this.handleEvent(event);
            },
        });

        this.logger.log('Kafka consumer started: channel.member.event');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleEvent(event: ChannelMemberEvent): Promise<void> {
        const { eventType, channelId, userId, role } = event;

        try {
            if (eventType === 'JOIN') {
                await this.memberModel.updateOne(
                    { channelId, userId },
                    { $set: { role } },
                    { upsert: true },
                );
            } else if (eventType === 'LEAVE') {
                await this.memberModel.deleteOne({ channelId, userId });
            } else if (eventType === 'ROLE_CHANGE') {
                await this.memberModel.updateOne({ channelId, userId }, { $set: { role } });
            }
        } catch (err) {
            this.logger.error(`멤버십 이벤트 처리 실패 [${eventType}]`, err);
            throw err;
        }
    }
}
