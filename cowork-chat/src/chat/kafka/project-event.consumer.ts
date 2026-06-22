import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { Server } from 'socket.io';
import { getRequiredCsvConfig } from '../../common/config/config.util';

interface ProjectEvent {
    eventType: 'CREATED' | 'UPDATED' | 'DELETED';
    projectId: number;
    teamId: number;
    name: string;
    description: string | null;
    status: string;
}

@Injectable()
export class ProjectEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ProjectEventConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(private readonly configService: ConfigService) {}

    setSocketServer(io: Server) {
        this.io = io;
    }

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-project-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.consumer = kafka.consumer({ groupId: 'cowork-chat-project-event' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: 'project.event', fromBeginning: false });

        void this.consumer
            .run({
                // eslint-disable-next-line @typescript-eslint/require-await -- kafkajs의 EachMessageHandler가 Promise<void> 반환을 요구함
                eachMessage: async ({ message }) => {
                    if (!message.value) return;
                    try {
                        const event = JSON.parse(message.value.toString()) as ProjectEvent;
                        this.handleEvent(event);
                    } catch (err) {
                        this.logger.error('프로젝트 이벤트 Kafka 메시지 처리 중 예외 발생', err);
                        if (!(err instanceof SyntaxError)) throw err;
                    }
                },
            })
            .catch((err) => {
                this.logger.error('프로젝트 이벤트 Kafka consumer 실행 실패', err);
                process.exit(1);
            });
        this.logger.log('Kafka consumer started: project.event');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private handleEvent(event: ProjectEvent) {
        if (!this.io) {
            throw new Error('Socket.IO server is not initialized yet');
        }
        if (!event || !event.eventType || !event.teamId) {
            this.logger.warn('유효하지 않은 프로젝트 이벤트 페이로드입니다: ' + JSON.stringify(event));
            return;
        }
        const room = `team:${event.teamId}`;
        const { eventType, ...payload } = event;

        if (eventType === 'CREATED') {
            this.io.to(room).emit('project:created', payload);
        } else if (eventType === 'UPDATED') {
            this.io.to(room).emit('project:updated', payload);
        } else if (eventType === 'DELETED') {
            this.io.to(room).emit('project:deleted', { projectId: event.projectId, teamId: event.teamId });
        }
    }
}
