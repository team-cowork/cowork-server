import {Logger, ValidationPipe} from '@nestjs/common';
import {NestFactory} from '@nestjs/core';
import {type MicroserviceOptions, Transport} from '@nestjs/microservices';
import {Logger as PinoLogger} from 'nestjs-pino';
import {AppConfigService} from './config/app-config.service';

async function bootstrap() {
    const {AppModule} = await import('./app.module');
    const app = await NestFactory.create(AppModule, {rawBody: true, bufferLogs: true});
    app.useLogger(app.get(PinoLogger));
    const config = app.get(AppConfigService);

    app.connectMicroservice<MicroserviceOptions>({
        transport: Transport.KAFKA,
        options: {
            client: {
                ...config.kafkaClientConfig,
            },
            consumer: {
                groupId: config.kafkaGroupId,
                allowAutoTopicCreation: false,
            },
            // Topics without a committed offset start from the beginning so commands published
            // before this consumer first joins are not skipped.
            subscribe: {
                fromBeginning: true,
            },
            run: {
                autoCommit: false,
            },
        },
    });

    app.useGlobalPipes(new ValidationPipe({whitelist: true}));

    await app.startAllMicroservices();
    await app.listen(config.port);

    const handleSignal = async (signal: NodeJS.Signals) => {
        try {
            await app.close();
        } finally {
            // Re-raise after cleanup so the default handler terminates despite open Redis sockets.
            process.kill(process.pid, signal);
        }
    };

    for (const signal of ['SIGTERM', 'SIGINT'] as const) {
        process.once(signal, () => {
            void handleSignal(signal);
        });
    }
}

bootstrap().catch((error: unknown) => {
    new Logger('Bootstrap').error('Application failed to start', error instanceof Error ? error.stack : String(error));
    // eslint-disable-next-line unicorn/no-process-exit -- Failed startup must terminate even when Redis clients have open sockets.
    process.exit(1);
});
