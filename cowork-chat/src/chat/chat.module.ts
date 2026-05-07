import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { MongooseModule } from '@nestjs/mongoose';
import { PrometheusModule } from '@willsoto/nestjs-prometheus';
import { LoggerModule } from 'nestjs-pino';
import { createWriteStream, mkdirSync } from 'fs';
import { ChatGateway } from './chat.gateway';
import { ChatService } from './chat.service';
import { ChatController } from './chat.controller';
import { ChatMessageProducer } from './kafka/chat-message.producer';
import { ChatMessageConsumer } from './kafka/chat-message.consumer';
import { NotificationTriggerProducer } from './kafka/notification-trigger.producer';
import { NotificationOutboxPoller } from './kafka/notification-outbox.poller';
import { GithubIssueProducer } from './kafka/github-issue.producer';
import { GithubIssueResultConsumer } from './kafka/github-issue-result.consumer';
import { ProjectClient } from './service/project.client';
import { Message, MessageSchema } from './schema/message.schema';
import { ChannelMember, ChannelMemberSchema } from './schema/channel-member.schema';
import { MembershipModule } from '../membership/membership.module';
import { HealthController } from '../health.controller';
import { MinioModule } from '../storage/minio.module';
import { getRequiredConfig } from '../common/config/config.util';

const LOG_DIR = process.env.COWORK_CHAT_LOG_DIR ?? `${process.cwd()}/build/logs/cowork/chat`;
const METRICS_PATH = '/metrics';
const HEALTH_PATH = '/health';
const EXCLUDED_AUTO_LOGGING_PATHS = new Set([METRICS_PATH, HEALTH_PATH]);

const loggerImports = process.env.CHAT_LOGGER_ENABLED === 'false'
    ? []
    : [
        LoggerModule.forRoot({
            pinoHttp: {
                level: process.env.NODE_ENV === 'production' ? 'info' : 'debug',
                stream: createLogStream(),
                autoLogging: {
                    ignore: (req) => {
                        const path = req.url?.split('?')[0]?.replace(/\/$/, '');
                        return path !== undefined && EXCLUDED_AUTO_LOGGING_PATHS.has(path);
                    },
                },
                timestamp: () => `,"@timestamp":"${new Date().toISOString()}"`,
                formatters: {
                    level: (label: string) => ({ level: label }),
                },
                base: { service: 'cowork-chat' },
                messageKey: 'message',
            },
        }),
    ];

function createLogStream() {
    try {
        mkdirSync(LOG_DIR, { recursive: true });
        return createWriteStream(`${LOG_DIR}/app.log`, { flags: 'a' });
    } catch {
        return process.stdout;
    }
}

@Module({
    imports: [
        ConfigModule,
        ...loggerImports,
        PrometheusModule.register({
            defaultMetrics: { enabled: true },
            path: '/metrics',
        }),
        MongooseModule.forRootAsync({
            imports: [ConfigModule],
            inject: [ConfigService],
            useFactory: (configService: ConfigService) => ({
                uri: getRequiredConfig(configService, 'MONGODB_URI'),
                serverSelectionTimeoutMS: Number(configService.get<string>('MONGODB_SERVER_SELECTION_TIMEOUT_MS') ?? 5000),
                connectTimeoutMS: Number(configService.get<string>('MONGODB_CONNECT_TIMEOUT_MS') ?? 5000),
                directConnection: (configService.get<string>('MONGODB_DIRECT_CONNECTION') ?? 'true') !== 'false',
            }),
        }),
        MongooseModule.forFeature([
            { name: Message.name, schema: MessageSchema },
            { name: ChannelMember.name, schema: ChannelMemberSchema },
        ]),
        MembershipModule,
        MinioModule,
    ],
    controllers: [ChatController, HealthController],
    providers: [
        ChatGateway,
        ChatService,
        ChatMessageProducer,
        ChatMessageConsumer,
        NotificationTriggerProducer,
        NotificationOutboxPoller,
        GithubIssueProducer,
        GithubIssueResultConsumer,
        ProjectClient,
    ],
})
export class ChatModule {}
