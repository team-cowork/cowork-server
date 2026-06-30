import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { Server } from 'socket.io';
import { DicoshotService } from 'dicoshot-nest';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';

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

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
    ) {}

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
                eachMessage: ({ message }): Promise<void> => {
                    if (message.value) {
                        try {
                            const event = JSON.parse(message.value.toString()) as ProjectEvent;
                            this.handleEvent(event);
                        } catch (err) {
                            this.logger.error('Exception while processing project.event Kafka message', err);
                            if (!(err instanceof SyntaxError)) throw err;
                        }
                    }
                    return Promise.resolve();
                },
            })
            .catch(async (err) => {
                this.logger.error('project.event Kafka consumer failed', err);
                await this.dicoshot.sendCustom({
                    title: '🔴 Kafka Consumer 중단',
                    description: 'cowork-chat의 project.event consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
                    color: 'danger',
                    fields: [
                        { name: 'Topic', value: 'project.event', inline: true },
                        ...buildErrorFields(err),
                    ],
                }).catch(() => {});
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
            this.logger.warn('Invalid project event payload: ' + JSON.stringify(event));
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
