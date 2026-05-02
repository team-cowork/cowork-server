import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { Server } from 'socket.io';
import { ChatService } from '../chat.service';
import { GithubIssueResultEvent } from './event/github-issue.event';

@Injectable()
export class GithubIssueResultConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(GithubIssueResultConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(
        private readonly chatService: ChatService,
        private readonly configService: ConfigService,
    ) {}

    setSocketServer(io: Server) {
        this.io = io;
    }

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-github-result',
            brokers: [this.configService.get<string>('KAFKA_BOOTSTRAP_SERVERS', 'localhost:9092')],
        });
        this.consumer = kafka.consumer({ groupId: 'cowork-chat-github-result' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: 'github.issue.result', fromBeginning: false });

        await this.consumer.run({
            eachMessage: async ({ message }) => {
                if (!message.value) return;
                try {
                    const event: GithubIssueResultEvent = JSON.parse(message.value.toString());
                    await this.handleResultEvent(event);
                } catch (err) {
                    this.logger.error('이슈 결과 처리 중 오류 발생', err);
                    if (!(err instanceof SyntaxError)) throw err;
                }
            },
        });

        this.logger.log('Kafka consumer started: github.issue.result');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleResultEvent(event: GithubIssueResultEvent): Promise<void> {
        const content = this.formatIssueResultMessage(event);

        const saved = await this.chatService.saveSystemMessage(
            event.teamId,
            event.channelId,
            content,
            event.projectId ?? null,
        );

        this.notifyClient(event.channelId, saved.toObject());
    }

    private formatIssueResultMessage(event: GithubIssueResultEvent): string {
        if (event.success) {
            return `✅ 이슈가 생성됐어요: ${event.issueUrl}`;
        }
        return `❌ 이슈 생성 실패: ${event.error ?? '알 수 없는 오류'}`;
    }

    private notifyClient(channelId: number, message: any): void {
        this.io?.to(`chat:${channelId}`).emit('message', message);
    }
}
