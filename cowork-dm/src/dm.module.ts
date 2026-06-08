import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { JwtModule } from '@nestjs/jwt';
import { MongooseModule } from '@nestjs/mongoose';
import { PrometheusModule } from '@willsoto/nestjs-prometheus';
import { LoggerModule } from 'nestjs-pino';
import { createWriteStream, mkdirSync } from 'fs';
import { ConversationModule } from './conversation/conversation.module';
import { MessageModule } from './message/message.module';
import { BlockModule } from './block/block.module';
import { GatewayModule } from './gateway/gateway.module';
import { MinioModule } from './storage/minio.module';
import { StorageController } from './storage/storage.controller';
import { HealthController } from './health.controller';
import { getOptionalConfig, getRequiredConfig } from './common/config/config.util';

const LOG_DIR = process.env.COWORK_DM_LOG_DIR ?? `${process.cwd()}/build/logs/cowork/dm`;
const METRICS_PATH = '/metrics';
const HEALTH_PATH = '/health';
const EXCLUDED_AUTO_LOGGING_PATHS = new Set([METRICS_PATH, HEALTH_PATH]);

const loggerImports = process.env.DM_LOGGER_ENABLED === 'false'
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
                base: { service: 'cowork-dm' },
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
        ConfigModule.forRoot({ isGlobal: true }),
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
        ConversationModule,
        MessageModule,
        BlockModule,
        GatewayModule,
        MinioModule,
    ],
    controllers: [StorageController, HealthController],
})
export class DmModule {}
