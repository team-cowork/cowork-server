import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { DicoshotService } from 'dicoshot-nest';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';
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
        private readonly dicoshot: DicoshotService,
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
                eachMessage: async ({ message }): Promise<void> => {
                    if (message.value) {
                        try {
                            const event = JSON.parse(message.value.toString()) as ProjectMemberEvent;
                            await this.handleEvent(event);
                        } catch (err) {
                            this.logger.error('Exception while processing project.member.event Kafka message', err);
                            if (!(err instanceof SyntaxError)) throw err;
                        }
                    }
                },
            })
            .catch(async (err) => {
                this.logger.error('project.member.event Kafka consumer failed', err);
                await this.dicoshot.sendCustom({
                    title: '🔴 Kafka Consumer 중단',
                    description: 'cowork-chat의 project.member.event consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
                    color: 'danger',
                    fields: [
                        { name: 'Topic', value: 'project.member.event', inline: true },
                        ...buildErrorFields(err),
                    ],
                }).catch(() => {});
                process.exit(1);
            });
        this.logger.log('Kafka consumer started: project.member.event');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleEvent(event: ProjectMemberEvent): Promise<void> {
        // eventType 값과 무관하게 무효화한다. 무해하며 향후 이벤트 타입이 늘어도 안전하다.
        if (!event || !event.eventType || typeof event.projectId !== 'number' || typeof event.userId !== 'number') {
            this.logger.warn('Invalid project member event payload: ' + JSON.stringify(event));
            return;
        }
        await this.projectMemberCache.invalidate(event.projectId, event.userId);
    }
}
