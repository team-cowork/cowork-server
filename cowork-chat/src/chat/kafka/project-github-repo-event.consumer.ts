import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Consumer, Kafka } from 'kafkajs';
import { DicoshotService } from 'dicoshot-nest';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';
import { isSafePositiveInteger } from '../../common/util/safe-integer.util';
import { parseEventTime } from '../../common/util/event-time.util';
import { PROJECTION_STREAMS, ProjectionReadinessService } from '../../common/kafka/projection-readiness.service';
import { applyProjectionMessage, ProjectionContractError } from '../../common/kafka/projection-message.processor';
import { ProjectGithubRepoProjectionRepository } from '../repository/project-github-repo-projection.repository';

interface ProjectGithubRepoEvent {
    schemaVersion: 1;
    eventType: 'UPSERT' | 'DELETE';
    repoId: number;
    projectId: number;
    teamId: number;
    githubRepoUrl: string | null;
    owner: string | null;
    repo: string | null;
    webhookChannelId: number | null;
    occurredAt: string;
    snapshot?: boolean;
}

@Injectable()
export class ProjectGithubRepoEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ProjectGithubRepoEventConsumer.name);
    private consumer!: Consumer;

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly repository: ProjectGithubRepoProjectionRepository,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-project-github-repo-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        const stream = PROJECTION_STREAMS.projectGithubRepo;
        this.consumer = kafka.consumer({ groupId: stream.groupId });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: stream.topic, fromBeginning: true });
        await this.projectionReadiness.registerProjection(kafka, this.consumer, stream);

        void this.consumer.run({
            eachMessage: async ({ partition, message }): Promise<void> => {
                await this.projectionReadiness.processMessage(stream, partition, message.offset, async () => {
                    return applyProjectionMessage(
                        stream,
                        partition,
                        message,
                        this.projectionReadiness,
                        (payload, key) => this.handleEvent(payload, key),
                    );
                });
            },
        }).catch(async (error: unknown) => {
            this.logger.error('project.github-repo.event Kafka consumer failed', error);
            await this.dicoshot.sendCustom({
                title: '🔴 Kafka Consumer 중단',
                description: 'cowork-chat의 project.github-repo.event consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
                color: 'danger',
                fields: [
                    { name: 'Topic', value: stream.topic, inline: true },
                    ...buildErrorFields(error),
                ],
            }).catch(() => {});
            process.exit(1);
        });
        this.logger.log(`Kafka projection consumer started: ${stream.topic}`);
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
        if (!this.isProjectGithubRepoEvent(payload)) {
            throw new ProjectionContractError('invalid project GitHub repo event payload');
        }
        if (messageKey !== String(payload.repoId)) {
            throw new ProjectionContractError(
                `project GitHub repo event key mismatch [key=${messageKey ?? '<missing>'}, repoId=${payload.repoId}]`,
            );
        }
        const eventTime = parseEventTime(payload.occurredAt);
        if (!eventTime) throw new ProjectionContractError('project GitHub repo occurredAt must be RFC3339');
        const { occurredAt, sourceVersion } = eventTime;
        if (payload.eventType === 'DELETE') {
            await this.repository.remove(payload.repoId, occurredAt, sourceVersion);
            return;
        }
        if (!payload.githubRepoUrl || !payload.owner || !payload.repo) {
            throw new ProjectionContractError('project GitHub repo UPSERT requires URL, owner, and repo');
        }

        await this.repository.upsert({
            repoId: payload.repoId,
            projectId: payload.projectId,
            teamId: payload.teamId,
            githubRepoUrl: payload.githubRepoUrl,
            owner: payload.owner,
            repo: payload.repo,
            webhookChannelId: payload.webhookChannelId,
            occurredAt,
            sourceVersion,
        });
    }

    private isProjectGithubRepoEvent(payload: unknown): payload is ProjectGithubRepoEvent {
        if (typeof payload !== 'object' || payload === null) return false;
        const event = payload as Partial<ProjectGithubRepoEvent>;
        if (event.schemaVersion !== 1
            || (event.eventType !== 'UPSERT' && event.eventType !== 'DELETE')
            || !isSafePositiveInteger(event.repoId)
            || !isSafePositiveInteger(event.projectId)
            || !isSafePositiveInteger(event.teamId)
            || (event.webhookChannelId !== null && !isSafePositiveInteger(event.webhookChannelId))
            || parseEventTime(event.occurredAt) === null
            || (event.snapshot !== undefined && typeof event.snapshot !== 'boolean')) {
            return false;
        }
        if (event.eventType === 'DELETE') {
            return (event.githubRepoUrl === null || typeof event.githubRepoUrl === 'string')
                && (event.owner === null || typeof event.owner === 'string')
                && (event.repo === null || typeof event.repo === 'string');
        }
        return typeof event.githubRepoUrl === 'string'
            && typeof event.owner === 'string'
            && event.owner.length > 0
            && event.owner === event.owner.toLowerCase()
            && typeof event.repo === 'string'
            && event.repo.length > 0
            && event.repo === event.repo.toLowerCase();
    }
}
