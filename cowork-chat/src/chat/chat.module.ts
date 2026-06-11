import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { JwtModule } from '@nestjs/jwt';
import { MongooseModule } from '@nestjs/mongoose';
import { PrometheusModule } from '@willsoto/nestjs-prometheus';
import { LoggerModule } from 'nestjs-pino';
import { createWriteStream, mkdirSync } from 'fs';
import { ChatGateway } from './chat.gateway';
import { ChatService } from './chat.service';
import { ChatController } from './chat.controller';
import { DmController } from './dm.controller';
import { ProjectMessageController } from './project-message.controller';
import { TeamUnreadController } from './team-unread.controller';
import { TeamSearchController } from './team-search.controller';
import { ChatMessageProducer } from './kafka/chat-message.producer';
import { ChatMessageConsumer } from './kafka/chat-message.consumer';
import { NotificationTriggerProducer } from './kafka/notification-trigger.producer';
import { NotificationOutboxPoller } from './kafka/notification-outbox.poller';
import { GithubIssueProducer } from './kafka/github-issue.producer';
import { GithubIssueResultConsumer } from './kafka/github-issue-result.consumer';
import { ChannelEventConsumer } from './kafka/channel-event.consumer';
import { ProjectEventConsumer } from './kafka/project-event.consumer';
import { ProjectClient } from './service/project.client';
import { ChannelClient } from './service/channel.client';
import { UserClient } from './service/user.client';
import { Message, MessageSchema } from './schema/message.schema';
import { ChannelMember, ChannelMemberSchema } from './schema/channel-member.schema';
import { MessageRepository } from './repository/message.repository';
import { ChannelMemberRepository } from './repository/channel-member.repository';
import { MembershipModule } from '../membership/membership.module';
import { BlockModule } from '../block/block.module';
import { HealthController } from '../health.controller';
import { MinioModule } from '../storage/minio.module';
import { SearchModule } from '../search/search.module';
import { getOptionalConfig, getRequiredConfig } from '../common/config/config.util';

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

/** /metrics, /health 경로는 자동 로깅에서 제외하고 로그 파일에 기록하는 pino 스트림을 생성한다. */
function createLogStream() {
    try {
        mkdirSync(LOG_DIR, { recursive: true });
        return createWriteStream(`${LOG_DIR}/app.log`, { flags: 'a' });
    } catch {
        return process.stdout;
    }
}

/**
 * 채팅 서비스의 루트 모듈.
 *
 * WebSocket 게이트웨이, REST 컨트롤러, Kafka 프로듀서/컨슈머,
 * 알림 outbox 폴러, MongoDB 스키마, MinIO, Elasticsearch를 통합 관리한다.
 * `CHAT_LOGGER_ENABLED=false` 환경변수로 pino 로거를 비활성화할 수 있다.
 */
@Module({
    imports: [
        ConfigModule,
        JwtModule.registerAsync({
            imports: [ConfigModule],
            inject: [ConfigService],
            useFactory: (configService: ConfigService) => ({
                secret: getRequiredConfig(configService, 'JWT_SECRET'),
                signOptions: { algorithm: 'HS256' },
            }),
        }),
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
                serverSelectionTimeoutMS: Number(getOptionalConfig(configService, 'MONGODB_SERVER_SELECTION_TIMEOUT_MS') ?? 5000),
                connectTimeoutMS: Number(getOptionalConfig(configService, 'MONGODB_CONNECT_TIMEOUT_MS') ?? 5000),
                directConnection: (getOptionalConfig(configService, 'MONGODB_DIRECT_CONNECTION') ?? 'true') !== 'false',
            }),
        }),
        MongooseModule.forFeature([
            { name: Message.name, schema: MessageSchema },
            { name: ChannelMember.name, schema: ChannelMemberSchema },
        ]),
        MembershipModule,
        BlockModule,
        MinioModule,
        SearchModule,
    ],
    controllers: [ChatController, DmController, ProjectMessageController, TeamUnreadController, TeamSearchController, HealthController],
    providers: [
        ChatGateway,
        ChatService,
        MessageRepository,
        ChannelMemberRepository,
        ChatMessageProducer,
        ChatMessageConsumer,
        NotificationTriggerProducer,
        NotificationOutboxPoller,
        GithubIssueProducer,
        GithubIssueResultConsumer,
        ChannelEventConsumer,
        ProjectEventConsumer,
        ProjectClient,
        ChannelClient,
        UserClient,
    ],
})
export class ChatModule {}
