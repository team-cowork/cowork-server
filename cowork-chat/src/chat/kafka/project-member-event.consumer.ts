import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { ProjectMemberCache } from '../service/project-member.cache';

interface ProjectMemberEvent {
    eventType: 'ADDED' | 'REMOVED';
    projectId: number;
    userId: number;
}

/**
 * `project.member.event`를 구독해 `ProjectMemberCache`를 무효화한다.
 * project-service에서 멤버가 추가/제거되면 이 이벤트가 발행되며,
 * TTL(30초) 만료를 기다리지 않고 즉시 캐시를 갱신하기 위함이다.
 */
@Injectable()
export class ProjectMemberEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ProjectMemberEventConsumer.name);
    private consumer!: Consumer;

    constructor(
        private readonly configService: ConfigService,
        private readonly projectMemberCache: ProjectMemberCache,
    ) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-project-member-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.consumer = kafka.consumer({ groupId: 'cowork-chat-project-member-event' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: 'project.member.event', fromBeginning: false });

        void this.consumer
            .run({
                eachMessage: ({ message }): Promise<void> => {
                    if (message.value) {
                        try {
                            const event = JSON.parse(message.value.toString()) as ProjectMemberEvent;
                            this.handleEvent(event);
                        } catch (err) {
                            this.logger.error('Exception while processing project.member.event Kafka message', err);
                            if (!(err instanceof SyntaxError)) throw err;
                        }
                    }
                    return Promise.resolve();
                },
            })
            .catch((err) => this.logger.error('project.member.event Kafka consumer failed', err));
        this.logger.log('Kafka consumer started: project.member.event');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private handleEvent(event: ProjectMemberEvent) {
        if (!event || !event.eventType || !event.projectId || !event.userId) {
            this.logger.warn('Invalid project member event payload: ' + JSON.stringify(event));
            return;
        }
        void this.projectMemberCache.invalidate(event.projectId, event.userId);
    }
}
