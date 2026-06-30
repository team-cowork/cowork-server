import { Module } from '@nestjs/common';
import { APP_GUARD, APP_INTERCEPTOR } from '@nestjs/core';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { MongooseModule } from '@nestjs/mongoose';
import { GraphQLModule } from '@nestjs/graphql';
import { ApolloDriver, ApolloDriverConfig } from '@nestjs/apollo';
import { Request } from 'express';
import { PrometheusModule } from '@willsoto/nestjs-prometheus';
import { LoggerModule } from 'nestjs-pino';
import { createWriteStream, mkdirSync } from 'fs';
import { ChatModule } from './chat/chat.module';
import { HealthController } from './health.controller';
import { AuthGuard } from './common/guard/auth.guard';
import { HttpLoggingInterceptor } from './common/interceptor/http-logging.interceptor';
import { getOptionalConfig, getRequiredConfig } from './common/config/config.util';

const LOG_DIR = process.env.COWORK_CHAT_LOG_DIR ?? `${process.cwd()}/build/logs/cowork/chat`;
const METRICS_PATH = '/metrics';
const HEALTH_PATH = '/health';
const EXCLUDED_AUTO_LOGGING_PATHS = new Set([METRICS_PATH, HEALTH_PATH]);
const IS_PRODUCTION = process.env.NODE_ENV === 'production';

function createLogStream() {
    try {
        mkdirSync(LOG_DIR, { recursive: true });
        return createWriteStream(`${LOG_DIR}/app.log`, { flags: 'a' });
    } catch {
        return process.stdout;
    }
}

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

@Module({
    imports: [
        ConfigModule.forRoot({ isGlobal: true }),
        MongooseModule.forRootAsync({
            inject: [ConfigService],
            useFactory: (configService: ConfigService) => ({
                uri: getRequiredConfig(configService, 'MONGODB_URI'),
                serverSelectionTimeoutMS: Number(getOptionalConfig(configService, 'MONGODB_SERVER_SELECTION_TIMEOUT_MS') ?? 5000),
                connectTimeoutMS: Number(getOptionalConfig(configService, 'MONGODB_CONNECT_TIMEOUT_MS') ?? 5000),
                directConnection: (getOptionalConfig(configService, 'MONGODB_DIRECT_CONNECTION') ?? 'true') !== 'false',
            }),
        }),
        ...loggerImports,
        PrometheusModule.register({
            defaultMetrics: { enabled: true },
            path: '/metrics',
        }),
        GraphQLModule.forRoot<ApolloDriverConfig>({
            driver: ApolloDriver,
            autoSchemaFile: true,
            context: ({ req }: { req: Request }) => ({ req }),
        }),
        ChatModule,
    ],
    controllers: [HealthController],
    providers: [
        { provide: APP_GUARD, useClass: AuthGuard },
        { provide: APP_INTERCEPTOR, useClass: HttpLoggingInterceptor },
    ],
})
export class AppModule {}
