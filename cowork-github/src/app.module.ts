import {mkdirSync, createWriteStream} from 'node:fs';
import {Module} from '@nestjs/common';
import {ConfigModule, ConfigService} from '@nestjs/config';
import {LoggerModule} from 'nestjs-pino';
import {multistream} from 'pino';
import {AppController} from './app.controller';
import {AppConfigService} from './config/app-config.service';
import {envValidationSchema} from './config/env.validation';
import {GithubModule} from './github/github.module';
import {MetricsController} from './metrics/metrics.controller';

const EXCLUDED_AUTO_LOGGING_PATHS = new Set(['/health', '/metrics']);

@Module({
    imports: [
        ConfigModule.forRoot({
            isGlobal: true,
            ignoreEnvFile: true,
            validationSchema: envValidationSchema,
        }),
        LoggerModule.forRootAsync({
            inject: [ConfigService],
            useFactory(configService: ConfigService) {
                const logDir = configService.get<string>('COWORK_GITHUB_LOG_DIR') ?? process.env.COWORK_GITHUB_LOG_DIR;
                const nodeEnv = configService.get<string>('NODE_ENV') ?? process.env.NODE_ENV;
                const level = nodeEnv === 'production' ? 'info' : 'debug';
                // 운영 수집기는 Docker stdout을 읽으므로 stdout은 항상 쓴다. 파일은 로그 디렉터리를 지정한 로컬 이미지에서만 함께 쓴다.
                const stream = (() => {
                    if (logDir === undefined || logDir === '') {
                        return process.stdout;
                    }

                    try {
                        mkdirSync(logDir, {recursive: true});
                    } catch {
                        return process.stdout;
                    }

                    const file = createWriteStream(`${logDir}/app.log`, {flags: 'a'});
                    file.on('error', error => {
                        process.stderr.write(`cowork-github file log disabled: ${error.message}\n`);
                    });
                    return multistream([
                        {level, stream: process.stdout},
                        {level, stream: file},
                    ]);
                })();
                return {
                    pinoHttp: {
                        level,
                        stream,
                        autoLogging: {
                            ignore(request) {
                                const path = request.url?.split('?', 1)[0]?.replace(/\/$/v, '');
                                return path !== undefined && EXCLUDED_AUTO_LOGGING_PATHS.has(path);
                            },
                        },
                        timestamp: () => `,"@timestamp":"${new Date().toISOString()}"`,
                        formatters: {
                            level: (label: string) => ({level: label.toUpperCase()}),
                        },
                        base: {service: 'cowork-github'},
                        messageKey: 'message',
                    },
                    renameContext: 'module',
                };
            },
        }),
        GithubModule,
    ],
    providers: [AppConfigService],
    controllers: [AppController, MetricsController],
})
export class AppModule {}
