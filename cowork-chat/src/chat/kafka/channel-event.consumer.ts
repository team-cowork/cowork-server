import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { Server } from 'socket.io';
import { getRequiredCsvConfig } from '../../common/config/config.util';

interface ChannelEvent {
    eventType: 'CREATED' | 'UPDATED' | 'DELETED';
    channelId: number;
    teamId: number;
    name: string;
    type: string;
    viewType: string;
    description: string | null;
    isPrivate: boolean;
}

@Injectable()
export class ChannelEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChannelEventConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(private readonly configService: ConfigService) {}

    setSocketServer(io: Server) {
        this.io = io;
    }

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-channel-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.consumer = kafka.consumer({ groupId: 'cowork-chat-channel-event' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: 'channel.event', fromBeginning: false });

        void this.consumer
            .run({
                eachMessage: async ({ message }) => {
                    if (!message.value) return;
                    try {
                        const event: ChannelEvent = JSON.parse(message.value.toString());
                        this.handleEvent(event);
                    } catch (err) {
                        this.logger.error('채널 이벤트 Kafka 메시지 처리 중 예외 발생', err);
                        if (!(err instanceof SyntaxError)) throw err;
                    }
                },
            })
            .catch((err) => this.logger.error('채널 이벤트 Kafka consumer 실행 실패', err));
        this.logger.log('Kafka consumer started: channel.event');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private handleEvent(event: ChannelEvent) {
        if (!this.io) return;
        const room = `team:${event.teamId}`;
        const { eventType, ...payload } = event;

        if (eventType === 'CREATED') {
            this.io.to(room).emit('channel:created', payload);
        } else if (eventType === 'UPDATED') {
            this.io.to(room).emit('channel:updated', payload);
        } else if (eventType === 'DELETED') {
            this.io.to(room).emit('channel:deleted', { channelId: event.channelId, teamId: event.teamId });
        }
    }
}
